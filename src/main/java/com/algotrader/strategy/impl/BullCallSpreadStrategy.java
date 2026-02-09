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
 * Bull call spread strategy: buy lower-strike CE, sell higher-strike CE.
 *
 * <p><b>Market view:</b> Moderately bullish. Profits from upward movement in the
 * underlying up to the short call strike. Limited risk (net premium paid), limited
 * reward (strike difference - net premium).
 *
 * <p><b>Legs (2):</b>
 * <ol>
 *   <li>Buy CE at ATM + buyOffset (lower strike, long call)</li>
 *   <li>Sell CE at ATM + sellOffset (higher strike, short call)</li>
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
 * <p><b>Adjustment (roll down):</b> When current P&L drops below the negative roll
 * threshold, the buy leg is rolled down by one strike interval to reduce cost basis.
 * Actual roll execution via MultiLegExecutor is deferred to Phase 7 integration.
 *
 * <p><b>Monitoring interval:</b> 5 minutes (positional default).
 */
public class BullCallSpreadStrategy extends BaseStrategy {

    private static final Logger log = LoggerFactory.getLogger(BullCallSpreadStrategy.class);

    /** Positional strategies evaluate every 5 minutes. */
    private static final Duration MONITORING_INTERVAL = Duration.ofMinutes(5);

    private final BullCallSpreadConfig bullCallSpreadConfig;

    public BullCallSpreadStrategy(String id, String name, BullCallSpreadConfig bullCallSpreadConfig) {
        super(id, name, bullCallSpreadConfig);
        this.bullCallSpreadConfig = bullCallSpreadConfig;
    }

    // ========================
    // IDENTITY
    // ========================

    @Override
    public StrategyType getType() {
        return StrategyType.BULL_CALL_SPREAD;
    }

    @Override
    public Duration getMonitoringInterval() {
        return MONITORING_INTERVAL;
    }

    // ========================
    // ENTRY
    // ========================

    /**
     * Entry requires:
     * 1. Current time within the configured entry window
     * 2. Spot price at or above minimum entry level (if configured)
     */
    @Override
    protected boolean shouldEnter(MarketSnapshot snapshot) {
        if (!isWithinEntryWindow()) {
            return false;
        }

        BigDecimal minSpot = bullCallSpreadConfig.getMinSpotForEntry();
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
     * 1. Buy CE at ATM + buyOffset (lower strike)
     * 2. Sell CE at ATM + sellOffset (higher strike)
     */
    @Override
    protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
        BigDecimal atm = roundToStrike(snapshot.getSpotPrice());

        BigDecimal buyStrike = atm.add(bullCallSpreadConfig.getBuyOffset());
        BigDecimal sellStrike = atm.add(bullCallSpreadConfig.getSellOffset());

        return List.of(
                buildOrderRequest(buyStrike, "CE", OrderSide.BUY), buildOrderRequest(sellStrike, "CE", OrderSide.SELL));
    }

    // ========================
    // EXIT
    // ========================

    /**
     * Exit triggers on:
     * 1. Target profit reached (premium decay %)
     * 2. Stop loss hit (premium loss multiplier)
     * 3. DTE below minimum
     */
    @Override
    protected boolean shouldExit(MarketSnapshot snapshot) {
        if (isTargetReached(bullCallSpreadConfig.getTargetPercent())) {
            logDecision(
                    "EXIT_REASON",
                    "Target profit reached",
                    Map.of(
                            "targetPercent",
                            bullCallSpreadConfig.getTargetPercent().toString()));
            return true;
        }

        if (isStopLossHit(bullCallSpreadConfig.getStopLossMultiplier())) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss hit",
                    Map.of(
                            "stopLossMultiplier",
                            bullCallSpreadConfig.getStopLossMultiplier().toString()));
            return true;
        }

        if (isDteExitTriggered(bullCallSpreadConfig.getMinDaysToExpiry())) {
            logDecision(
                    "EXIT_REASON",
                    "DTE exit triggered",
                    Map.of("minDaysToExpiry", bullCallSpreadConfig.getMinDaysToExpiry()));
            return true;
        }

        return false;
    }

    // ========================
    // ADJUSTMENT (roll down)
    // ========================

    /**
     * P&L-based roll-down adjustment:
     * When current P&L drops below -(rollThreshold), roll the buy leg down
     * by one strike interval to reduce cost basis.
     *
     * <p>The actual roll execution (close old leg, open new leg) is deferred to Phase 7
     * when the full multi-leg executor integration is complete. For now, the adjustment
     * is logged and recorded for cooldown tracking.
     */
    @Override
    protected void adjust(MarketSnapshot snapshot) {
        BigDecimal rollThreshold = bullCallSpreadConfig.getRollThreshold();
        if (rollThreshold == null) {
            return;
        }

        BigDecimal pnl = calculateTotalPnl();

        // P&L below negative roll threshold: roll buy leg down
        if (pnl.compareTo(rollThreshold.negate()) <= 0) {
            Position buyLeg = getBuyLeg();
            if (buyLeg != null) {
                logDecision(
                        "ADJUST_EVAL",
                        "PnL below roll threshold, rolling buy leg down",
                        Map.of(
                                "pnl", pnl.toString(),
                                "threshold", rollThreshold.negate().toString(),
                                "buyLegSymbol", buyLeg.getTradingSymbol()));

                // #TODO Phase 7: Execute roll via multiLegExecutor
                //   closeOrder(buyLeg) + openOrder(buyLeg.strike - strikeInterval, CE, BUY)
                recordAdjustment("ROLL_BUY_DOWN");
            }
        }
    }

    // ========================
    // MORPHING
    // ========================

    /**
     * A bull call spread can morph into:
     * - Bear Call Spread (flip direction by swapping buy/sell legs)
     * - Iron Condor (add a put spread below)
     */
    @Override
    public List<StrategyType> supportedMorphs() {
        return List.of(StrategyType.BEAR_CALL_SPREAD, StrategyType.IRON_CONDOR);
    }

    // ========================
    // INTERNALS
    // ========================

    /** Finds the long call leg (quantity > 0, CE option). */
    private Position getBuyLeg() {
        return positions.stream()
                .filter(p -> p.getQuantity() > 0
                        && p.getTradingSymbol() != null
                        && p.getTradingSymbol().endsWith("CE"))
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
     * Builds a placeholder trading symbol for the order.
     * #TODO Task 8.3: Replace with InstrumentService.resolveSymbol() for full Kite symbol
     */
    private String buildTradingSymbol(BigDecimal strike, String optionType) {
        return bullCallSpreadConfig.getUnderlying()
                + strike.stripTrailingZeros().toPlainString()
                + optionType;
    }
}
