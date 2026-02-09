package com.algotrader.strategy.impl;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Position;
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
 * Bull put spread (credit put spread): sell higher-strike PE, buy lower-strike PE.
 *
 * <p><b>Market view:</b> Moderately bullish / neutral. A net credit strategy that
 * profits when the underlying stays above the short put strike. Maximum profit =
 * net premium collected. Maximum loss = (sellStrike - buyStrike - net premium).
 *
 * <p><b>Legs (2):</b>
 * <ol>
 *   <li>Sell PE at ATM + sellOffset (higher strike, short put)</li>
 *   <li>Buy PE at ATM + buyOffset (lower strike, long put / protection)</li>
 * </ol>
 *
 * <p><b>Entry conditions:</b>
 * <ul>
 *   <li>Within configured entry time window</li>
 *   <li>Spot price at or above minimum entry level (if configured)</li>
 * </ul>
 *
 * <p><b>Exit conditions:</b>
 * <ul>
 *   <li>Premium decay target: P&L >= entryPremium * targetPercent</li>
 *   <li>Stop loss: P&L <= -(entryPremium * stopLossMultiplier)</li>
 *   <li>DTE exit: days to expiry <= minDaysToExpiry</li>
 * </ul>
 *
 * <p><b>Adjustment (roll sell leg down):</b> When current P&L drops below the negative
 * roll threshold, the sell leg is rolled down by one strike interval to reduce exposure.
 *
 * <p><b>Monitoring interval:</b> 5 minutes (positional default).
 */
public class BullPutSpreadStrategy extends BaseStrategy {

    private static final Logger log = LoggerFactory.getLogger(BullPutSpreadStrategy.class);

    private static final Duration MONITORING_INTERVAL = Duration.ofMinutes(5);

    private final BullPutSpreadConfig bullPutSpreadConfig;

    public BullPutSpreadStrategy(String id, String name, BullPutSpreadConfig bullPutSpreadConfig) {
        super(id, name, bullPutSpreadConfig);
        this.bullPutSpreadConfig = bullPutSpreadConfig;
    }

    // ========================
    // IDENTITY
    // ========================

    @Override
    public StrategyType getType() {
        return StrategyType.BULL_PUT_SPREAD;
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

        BigDecimal minSpot = bullPutSpreadConfig.getMinSpotForEntry();
        if (minSpot != null) {
            BigDecimal spotPrice = snapshot.getSpotPrice();
            if (spotPrice == null || spotPrice.compareTo(minSpot) < 0) {
                return false;
            }
        }

        return true;
    }

    /**
     * Builds 2 legs at calculated strikes:
     * 1. Sell PE at ATM + sellOffset (higher strike)
     * 2. Buy PE at ATM + buyOffset (lower strike)
     */
    @Override
    protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
        BigDecimal atm = roundToStrike(snapshot.getSpotPrice());

        BigDecimal sellStrike = atm.add(bullPutSpreadConfig.getSellOffset());
        BigDecimal buyStrike = atm.add(bullPutSpreadConfig.getBuyOffset());

        return List.of(
                buildOrderRequest(sellStrike, "PE", OrderSide.SELL), buildOrderRequest(buyStrike, "PE", OrderSide.BUY));
    }

    // ========================
    // EXIT
    // ========================

    @Override
    protected boolean shouldExit(MarketSnapshot snapshot) {
        if (isTargetReached(bullPutSpreadConfig.getTargetPercent())) {
            logDecision(
                    "EXIT_REASON",
                    "Target profit reached",
                    Map.of(
                            "targetPercent",
                            bullPutSpreadConfig.getTargetPercent().toString()));
            return true;
        }

        if (isStopLossHit(bullPutSpreadConfig.getStopLossMultiplier())) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss hit",
                    Map.of(
                            "stopLossMultiplier",
                            bullPutSpreadConfig.getStopLossMultiplier().toString()));
            return true;
        }

        if (isDteExitTriggered(bullPutSpreadConfig.getMinDaysToExpiry())) {
            logDecision(
                    "EXIT_REASON",
                    "DTE exit triggered",
                    Map.of("minDaysToExpiry", bullPutSpreadConfig.getMinDaysToExpiry()));
            return true;
        }

        return false;
    }

    // ========================
    // ADJUSTMENT (roll sell leg down)
    // ========================

    /**
     * P&L-based roll-down adjustment for the sell leg:
     * When current P&L drops below -(rollThreshold), roll the sell leg down
     * by one strike interval to reduce exposure to the underlying's decline.
     */
    @Override
    protected void adjust(MarketSnapshot snapshot) {
        BigDecimal rollThreshold = bullPutSpreadConfig.getRollThreshold();
        if (rollThreshold == null) {
            return;
        }

        BigDecimal pnl = calculateTotalPnl();

        if (pnl.compareTo(rollThreshold.negate()) <= 0) {
            Position sellLeg = getSellLeg();
            if (sellLeg != null) {
                logDecision(
                        "ADJUST_EVAL",
                        "PnL below roll threshold, rolling sell leg down",
                        Map.of(
                                "pnl", pnl.toString(),
                                "threshold", rollThreshold.negate().toString(),
                                "sellLegSymbol", sellLeg.getTradingSymbol()));

                // #TODO Execute roll via multiLegExecutor
                recordAdjustment("ROLL_SELL_DOWN");
            }
        }
    }

    // ========================
    // MORPHING
    // ========================

    /**
     * A bull put spread can morph into:
     * - Bear Put Spread (flip direction by swapping buy/sell legs)
     * - Iron Condor (add a call spread above)
     */
    @Override
    public List<StrategyType> supportedMorphs() {
        return List.of(StrategyType.BEAR_PUT_SPREAD, StrategyType.IRON_CONDOR);
    }

    // ========================
    // INTERNALS
    // ========================

    /** Finds the short put leg (quantity < 0, PE option). */
    private Position getSellLeg() {
        return positions.stream()
                .filter(p -> p.getQuantity() < 0
                        && p.getTradingSymbol() != null
                        && p.getTradingSymbol().endsWith("PE"))
                .findFirst()
                .orElse(null);
    }

    private OrderRequest buildOrderRequest(BigDecimal strike, String optionType, OrderSide side) {
        return OrderRequest.builder()
                .tradingSymbol(buildTradingSymbol(strike, optionType))
                .side(side)
                .type(OrderType.MARKET)
                .quantity(1)
                .build();
    }

    /**
     * #TODO Replace with InstrumentService.resolveSymbol() for full Kite symbol
     */
    private String buildTradingSymbol(BigDecimal strike, String optionType) {
        return bullPutSpreadConfig.getUnderlying() + strike.stripTrailingZeros().toPlainString() + optionType;
    }
}
