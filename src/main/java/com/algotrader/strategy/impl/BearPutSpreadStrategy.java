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
 * Bear put spread strategy: buy higher-strike PE, sell lower-strike PE.
 *
 * <p><b>Market view:</b> Moderately bearish. Profits from downward movement in the
 * underlying down to the short put strike. Limited risk (net premium paid), limited
 * reward (strike difference - net premium).
 *
 * <p><b>Legs (2):</b>
 * <ol>
 *   <li>Buy PE at ATM + buyOffset (higher strike, long put)</li>
 *   <li>Sell PE at ATM + sellOffset (lower strike, short put)</li>
 * </ol>
 *
 * <p><b>Entry conditions:</b>
 * <ul>
 *   <li>Within configured entry time window</li>
 *   <li>Spot price at or below maximum entry level (if configured)</li>
 * </ul>
 *
 * <p><b>Exit conditions:</b>
 * <ul>
 *   <li>Premium decay target: P&L >= entryPremium * targetPercent</li>
 *   <li>Stop loss: P&L <= -(entryPremium * stopLossMultiplier)</li>
 *   <li>DTE exit: days to expiry <= minDaysToExpiry</li>
 * </ul>
 *
 * <p><b>Adjustment (roll up):</b> When current P&L drops below the negative roll
 * threshold, the buy leg is rolled up by one strike interval to increase intrinsic value.
 *
 * <p><b>Monitoring interval:</b> 5 minutes (positional default).
 */
public class BearPutSpreadStrategy extends BaseStrategy {

    private static final Logger log = LoggerFactory.getLogger(BearPutSpreadStrategy.class);

    private static final Duration MONITORING_INTERVAL = Duration.ofMinutes(5);

    private final BearPutSpreadConfig bearPutSpreadConfig;

    public BearPutSpreadStrategy(String id, String name, BearPutSpreadConfig bearPutSpreadConfig) {
        super(id, name, bearPutSpreadConfig);
        this.bearPutSpreadConfig = bearPutSpreadConfig;
    }

    // ========================
    // IDENTITY
    // ========================

    @Override
    public StrategyType getType() {
        return StrategyType.BEAR_PUT_SPREAD;
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
     * 2. Spot price at or below maximum entry level (if configured)
     */
    @Override
    protected boolean shouldEnter(MarketSnapshot snapshot) {
        if (!isWithinEntryWindow()) {
            return false;
        }

        BigDecimal maxSpot = bearPutSpreadConfig.getMaxSpotForEntry();
        if (maxSpot != null) {
            BigDecimal spotPrice = snapshot.getSpotPrice();
            if (spotPrice == null || spotPrice.compareTo(maxSpot) > 0) {
                return false;
            }
        }

        return true;
    }

    /**
     * Builds 2 legs at calculated strikes:
     * 1. Buy PE at ATM + buyOffset (higher strike, long put)
     * 2. Sell PE at ATM + sellOffset (lower strike, short put)
     */
    @Override
    protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
        BigDecimal atm = roundToStrike(snapshot.getSpotPrice());

        BigDecimal buyStrike = atm.add(bearPutSpreadConfig.getBuyOffset());
        BigDecimal sellStrike = atm.add(bearPutSpreadConfig.getSellOffset());

        return List.of(
                buildOrderRequest(buyStrike, "PE", OrderSide.BUY), buildOrderRequest(sellStrike, "PE", OrderSide.SELL));
    }

    // ========================
    // EXIT
    // ========================

    @Override
    protected boolean shouldExit(MarketSnapshot snapshot) {
        if (isTargetReached(bearPutSpreadConfig.getTargetPercent())) {
            logDecision(
                    "EXIT_REASON",
                    "Target profit reached",
                    Map.of(
                            "targetPercent",
                            bearPutSpreadConfig.getTargetPercent().toString()));
            return true;
        }

        if (isStopLossHit(bearPutSpreadConfig.getStopLossMultiplier())) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss hit",
                    Map.of(
                            "stopLossMultiplier",
                            bearPutSpreadConfig.getStopLossMultiplier().toString()));
            return true;
        }

        if (isDteExitTriggered(bearPutSpreadConfig.getMinDaysToExpiry())) {
            logDecision(
                    "EXIT_REASON",
                    "DTE exit triggered",
                    Map.of("minDaysToExpiry", bearPutSpreadConfig.getMinDaysToExpiry()));
            return true;
        }

        return false;
    }

    // ========================
    // ADJUSTMENT (roll up)
    // ========================

    /**
     * P&L-based roll-up adjustment:
     * When current P&L drops below -(rollThreshold), roll the buy leg up
     * by one strike interval to increase intrinsic value.
     */
    @Override
    protected void adjust(MarketSnapshot snapshot) {
        BigDecimal rollThreshold = bearPutSpreadConfig.getRollThreshold();
        if (rollThreshold == null) {
            return;
        }

        BigDecimal pnl = calculateTotalPnl();

        if (pnl.compareTo(rollThreshold.negate()) <= 0) {
            Position buyLeg = getBuyLeg();
            if (buyLeg != null) {
                logDecision(
                        "ADJUST_EVAL",
                        "PnL below roll threshold, rolling buy leg up",
                        Map.of(
                                "pnl", pnl.toString(),
                                "threshold", rollThreshold.negate().toString(),
                                "buyLegSymbol", buyLeg.getTradingSymbol()));

                // #TODO Execute roll via multiLegExecutor
                recordAdjustment("ROLL_BUY_UP");
            }
        }
    }

    // ========================
    // MORPHING
    // ========================

    /**
     * A bear put spread can morph into:
     * - Bull Put Spread (flip direction by swapping buy/sell legs)
     * - Iron Condor (add a call spread above)
     */
    @Override
    public List<StrategyType> supportedMorphs() {
        return List.of(StrategyType.BULL_PUT_SPREAD, StrategyType.IRON_CONDOR);
    }

    // ========================
    // INTERNALS
    // ========================

    /** Finds the long put leg (quantity > 0, PE option). */
    private Position getBuyLeg() {
        return positions.stream()
                .filter(p -> p.getQuantity() > 0
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
        return bearPutSpreadConfig.getUnderlying() + strike.stripTrailingZeros().toPlainString() + optionType;
    }
}
