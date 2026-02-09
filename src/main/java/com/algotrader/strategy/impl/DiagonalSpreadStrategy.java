package com.algotrader.strategy.impl;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.oms.OrderRequest;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.MarketSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagonal spread: different strikes AND different expiries for the two legs.
 *
 * <p><b>Market view:</b> Directional with theta benefit. Combines the time decay
 * advantage of a calendar spread with the directional exposure of a vertical spread.
 * Also known as "poor man's covered call/put" when using deep ITM/ATM far-leg.
 *
 * <p><b>Legs (2, different strikes and expiries):</b>
 * <ol>
 *   <li>Sell option at ATM + nearStrikeOffset + near expiry (short leg)</li>
 *   <li>Buy option at ATM + farStrikeOffset + far expiry (long leg)</li>
 * </ol>
 *
 * <p><b>Entry conditions:</b>
 * <ul>
 *   <li>Within configured entry time window</li>
 *   <li>ATM implied volatility >= configured minimum (if set)</li>
 * </ul>
 *
 * <p><b>Exit conditions:</b>
 * <ul>
 *   <li>Premium decay target: P&L >= entryPremium * targetPercent</li>
 *   <li>Stop loss: P&L <= -(entryPremium * stopLossMultiplier)</li>
 *   <li>DTE exit: near-term expiry approaching</li>
 * </ul>
 *
 * <p><b>Adjustment:</b> When P&L drops below the roll threshold, close the entire
 * spread. Diagonal spreads can also be "rolled forward" by closing the near-term
 * leg and selling a new near-term option at a different strike, but this is deferred
 * to full multi-leg executor integration.
 *
 * <p><b>Multi-expiry tracking:</b> The near and far legs have different expiry dates
 * AND different strikes. This makes diagonal spreads the most complex multi-expiry
 * strategy in terms of position tracking.
 *
 * <p><b>Monitoring interval:</b> 5 minutes (positional default).
 */
public class DiagonalSpreadStrategy extends BaseStrategy {

    private static final Logger log = LoggerFactory.getLogger(DiagonalSpreadStrategy.class);

    private static final Duration MONITORING_INTERVAL = Duration.ofMinutes(5);

    private final DiagonalSpreadConfig diagonalConfig;

    public DiagonalSpreadStrategy(String id, String name, DiagonalSpreadConfig diagonalConfig) {
        super(id, name, diagonalConfig);
        this.diagonalConfig = diagonalConfig;
    }

    // ========================
    // IDENTITY
    // ========================

    @Override
    public StrategyType getType() {
        return StrategyType.DIAGONAL_SPREAD;
    }

    @Override
    public Duration getMonitoringInterval() {
        return MONITORING_INTERVAL;
    }

    // ========================
    // ENTRY
    // ========================

    @Override
    protected boolean shouldEnter(MarketSnapshot snapshot) {
        if (!isWithinEntryWindow()) {
            return false;
        }

        BigDecimal minIV = diagonalConfig.getMinEntryIV();
        BigDecimal atmIV = snapshot.getAtmIV();

        if (minIV != null && (atmIV == null || atmIV.compareTo(minIV) < 0)) {
            return false;
        }

        return true;
    }

    /**
     * Builds 2 legs at different strikes and different expiries:
     * 1. Sell option at ATM + nearStrikeOffset + near expiry
     * 2. Buy option at ATM + farStrikeOffset + far expiry
     */
    @Override
    protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
        BigDecimal atm = roundToStrike(snapshot.getSpotPrice());
        String optionType = diagonalConfig.getOptionType();

        BigDecimal nearStrike = atm.add(diagonalConfig.getNearStrikeOffset());
        BigDecimal farStrike = atm.add(diagonalConfig.getFarStrikeOffset());

        // Near-term leg (sell)
        OrderRequest sellNear = OrderRequest.builder()
                .tradingSymbol(buildTradingSymbol(nearStrike, optionType, "NEAR"))
                .side(OrderSide.SELL)
                .type(OrderType.MARKET)
                .quantity(1)
                .build();

        // Far-term leg (buy)
        OrderRequest buyFar = OrderRequest.builder()
                .tradingSymbol(buildTradingSymbol(farStrike, optionType, "FAR"))
                .side(OrderSide.BUY)
                .type(OrderType.MARKET)
                .quantity(1)
                .build();

        return List.of(sellNear, buyFar);
    }

    // ========================
    // EXIT
    // ========================

    @Override
    protected boolean shouldExit(MarketSnapshot snapshot) {
        if (isTargetReached(diagonalConfig.getTargetPercent())) {
            logDecision(
                    "EXIT_REASON",
                    "Target profit reached",
                    Map.of("targetPercent", diagonalConfig.getTargetPercent().toString()));
            return true;
        }

        if (isStopLossHit(diagonalConfig.getStopLossMultiplier())) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss hit",
                    Map.of(
                            "stopLossMultiplier",
                            diagonalConfig.getStopLossMultiplier().toString()));
            return true;
        }

        if (isDteExitTriggered(diagonalConfig.getMinDaysToExpiry())) {
            logDecision(
                    "EXIT_REASON",
                    "Near-term DTE exit triggered",
                    Map.of("minDaysToExpiry", diagonalConfig.getMinDaysToExpiry()));
            return true;
        }

        return false;
    }

    // ========================
    // ADJUSTMENT (early close)
    // ========================

    /**
     * Diagonal spreads close entirely rather than rolling individual legs.
     * When P&L drops below the roll threshold, initiate close.
     *
     * #TODO Phase 14: Support rolling the near-term leg forward to a new expiry/strike
     */
    @Override
    protected void adjust(MarketSnapshot snapshot) {
        BigDecimal rollThreshold = diagonalConfig.getRollThreshold();
        if (rollThreshold == null) {
            return;
        }

        BigDecimal pnl = calculateTotalPnl();

        if (pnl.compareTo(rollThreshold.negate()) <= 0) {
            logDecision(
                    "ADJUST_EVAL",
                    "PnL below threshold, closing diagonal spread",
                    Map.of(
                            "pnl", pnl.toString(),
                            "threshold", rollThreshold.negate().toString()));

            initiateClose();
            recordAdjustment("DIAGONAL_CLOSE");
        }
    }

    // ========================
    // MORPHING
    // ========================

    /**
     * A diagonal spread can morph into:
     * - Calendar Spread (equalize strikes to make it a pure calendar)
     */
    @Override
    public List<StrategyType> supportedMorphs() {
        return List.of(StrategyType.CALENDAR_SPREAD);
    }

    // ========================
    // INTERNALS
    // ========================

    /**
     * Builds a placeholder trading symbol with expiry marker.
     * #TODO Replace with InstrumentService.resolveSymbol() for full Kite symbol
     */
    private String buildTradingSymbol(BigDecimal strike, String optionType, String expiryMarker) {
        return diagonalConfig.getUnderlying()
                + strike.stripTrailingZeros().toPlainString()
                + optionType
                + "-" + expiryMarker;
    }
}
