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
 * Calendar spread (horizontal spread): sell near-term option, buy far-term option at the same strike.
 *
 * <p><b>Market view:</b> Neutral. Profits from the near-term option decaying faster
 * than the far-term option. The time differential creates a positive theta position.
 * Also benefits from IV increase in the far-term leg (vega positive).
 *
 * <p><b>Legs (2, different expiries):</b>
 * <ol>
 *   <li>Sell option at strike + near expiry (short leg, faster decay)</li>
 *   <li>Buy option at strike + far expiry (long leg, slower decay)</li>
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
 * spread rather than rolling. Calendar spreads are difficult to roll effectively
 * since both legs need to move in sync.
 *
 * <p><b>Multi-expiry tracking:</b> The near and far legs have different expiry dates.
 * The strategy tracks both via the config's nearExpiry/farExpiry fields. The "expiry"
 * field in BaseStrategyConfig refers to the near-term (short) expiry since that drives
 * the strategy's DTE-based exit.
 *
 * <p><b>Monitoring interval:</b> 5 minutes (positional default).
 */
public class CalendarSpreadStrategy extends BaseStrategy {

    private static final Logger log = LoggerFactory.getLogger(CalendarSpreadStrategy.class);

    private static final Duration MONITORING_INTERVAL = Duration.ofMinutes(5);

    private final CalendarSpreadConfig calendarConfig;

    public CalendarSpreadStrategy(String id, String name, CalendarSpreadConfig calendarConfig) {
        super(id, name, calendarConfig);
        this.calendarConfig = calendarConfig;
    }

    // ========================
    // IDENTITY
    // ========================

    @Override
    public StrategyType getType() {
        return StrategyType.CALENDAR_SPREAD;
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

        BigDecimal minIV = calendarConfig.getMinEntryIV();
        BigDecimal atmIV = snapshot.getAtmIV();

        if (minIV != null && (atmIV == null || atmIV.compareTo(minIV) < 0)) {
            return false;
        }

        return true;
    }

    /**
     * Builds 2 legs at the same strike but different expiries:
     * 1. Sell option at ATM + strikeOffset + near expiry
     * 2. Buy option at ATM + strikeOffset + far expiry
     *
     * <p>Note: The expiry is encoded in a suffix for now. Actual Kite symbol resolution
     * with expiry happens at order execution time via InstrumentService.
     */
    @Override
    protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
        BigDecimal atm = roundToStrike(snapshot.getSpotPrice());
        BigDecimal strikeOffset = calendarConfig.getStrikeOffset();
        BigDecimal strike = strikeOffset != null ? atm.add(strikeOffset) : atm;
        String optionType = calendarConfig.getOptionType();

        // Near-term leg (sell, faster decay)
        OrderRequest sellNear = OrderRequest.builder()
                .tradingSymbol(buildTradingSymbol(strike, optionType, "NEAR"))
                .side(OrderSide.SELL)
                .type(OrderType.MARKET)
                .quantity(1)
                .build();

        // Far-term leg (buy, slower decay)
        OrderRequest buyFar = OrderRequest.builder()
                .tradingSymbol(buildTradingSymbol(strike, optionType, "FAR"))
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
        if (isTargetReached(calendarConfig.getTargetPercent())) {
            logDecision(
                    "EXIT_REASON",
                    "Target profit reached",
                    Map.of("targetPercent", calendarConfig.getTargetPercent().toString()));
            return true;
        }

        if (isStopLossHit(calendarConfig.getStopLossMultiplier())) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss hit",
                    Map.of(
                            "stopLossMultiplier",
                            calendarConfig.getStopLossMultiplier().toString()));
            return true;
        }

        if (isDteExitTriggered(calendarConfig.getMinDaysToExpiry())) {
            logDecision(
                    "EXIT_REASON",
                    "Near-term DTE exit triggered",
                    Map.of("minDaysToExpiry", calendarConfig.getMinDaysToExpiry()));
            return true;
        }

        return false;
    }

    // ========================
    // ADJUSTMENT (early close)
    // ========================

    /**
     * Calendar spreads close entirely rather than rolling individual legs.
     * When P&L drops below the roll threshold, initiate close.
     */
    @Override
    protected void adjust(MarketSnapshot snapshot) {
        BigDecimal rollThreshold = calendarConfig.getRollThreshold();
        if (rollThreshold == null) {
            return;
        }

        BigDecimal pnl = calculateTotalPnl();

        if (pnl.compareTo(rollThreshold.negate()) <= 0) {
            logDecision(
                    "ADJUST_EVAL",
                    "PnL below threshold, closing calendar spread",
                    Map.of(
                            "pnl", pnl.toString(),
                            "threshold", rollThreshold.negate().toString()));

            initiateClose();
            recordAdjustment("CALENDAR_CLOSE");
        }
    }

    // ========================
    // MORPHING
    // ========================

    /**
     * A calendar spread can morph into:
     * - Diagonal Spread (shift one strike to create a diagonal)
     */
    @Override
    public List<StrategyType> supportedMorphs() {
        return List.of(StrategyType.DIAGONAL_SPREAD);
    }

    // ========================
    // INTERNALS
    // ========================

    /**
     * Builds a placeholder trading symbol with expiry marker.
     * Format: {UNDERLYING}{STRIKE}{optionType}-{expiryMarker}
     * The actual Kite-compatible symbol with real expiry is resolved by InstrumentService.
     *
     * #TODO Replace with InstrumentService.resolveSymbol() for full Kite symbol
     */
    private String buildTradingSymbol(BigDecimal strike, String optionType, String expiryMarker) {
        return calendarConfig.getUnderlying()
                + strike.stripTrailingZeros().toPlainString()
                + optionType
                + "-" + expiryMarker;
    }
}
