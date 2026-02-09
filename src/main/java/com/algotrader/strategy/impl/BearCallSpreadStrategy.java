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
 * Bear call spread (credit call spread): sell lower-strike CE, buy higher-strike CE.
 *
 * <p><b>Market view:</b> Moderately bearish / neutral. A net credit strategy that
 * profits when the underlying stays below the short call strike. Maximum profit =
 * net premium collected. Maximum loss = (buyStrike - sellStrike - net premium).
 *
 * <p><b>Legs (2):</b>
 * <ol>
 *   <li>Sell CE at ATM + sellOffset (lower strike, short call)</li>
 *   <li>Buy CE at ATM + buyOffset (higher strike, long call / protection)</li>
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
 * <p><b>Adjustment (roll sell leg up):</b> When current P&L drops below the negative
 * roll threshold, the sell leg is rolled up by one strike interval to move away from
 * the approaching underlying.
 *
 * <p><b>Monitoring interval:</b> 5 minutes (positional default).
 */
public class BearCallSpreadStrategy extends BaseStrategy {

    private static final Logger log = LoggerFactory.getLogger(BearCallSpreadStrategy.class);

    private static final Duration MONITORING_INTERVAL = Duration.ofMinutes(5);

    private final BearCallSpreadConfig bearCallSpreadConfig;

    public BearCallSpreadStrategy(String id, String name, BearCallSpreadConfig bearCallSpreadConfig) {
        super(id, name, bearCallSpreadConfig);
        this.bearCallSpreadConfig = bearCallSpreadConfig;
    }

    // ========================
    // IDENTITY
    // ========================

    @Override
    public StrategyType getType() {
        return StrategyType.BEAR_CALL_SPREAD;
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

        BigDecimal maxSpot = bearCallSpreadConfig.getMaxSpotForEntry();
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
     * 1. Sell CE at ATM + sellOffset (lower strike)
     * 2. Buy CE at ATM + buyOffset (higher strike)
     */
    @Override
    protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
        BigDecimal atm = roundToStrike(snapshot.getSpotPrice());

        BigDecimal sellStrike = atm.add(bearCallSpreadConfig.getSellOffset());
        BigDecimal buyStrike = atm.add(bearCallSpreadConfig.getBuyOffset());

        return List.of(
                buildOrderRequest(sellStrike, "CE", OrderSide.SELL), buildOrderRequest(buyStrike, "CE", OrderSide.BUY));
    }

    // ========================
    // EXIT
    // ========================

    @Override
    protected boolean shouldExit(MarketSnapshot snapshot) {
        if (isTargetReached(bearCallSpreadConfig.getTargetPercent())) {
            logDecision(
                    "EXIT_REASON",
                    "Target profit reached",
                    Map.of(
                            "targetPercent",
                            bearCallSpreadConfig.getTargetPercent().toString()));
            return true;
        }

        if (isStopLossHit(bearCallSpreadConfig.getStopLossMultiplier())) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss hit",
                    Map.of(
                            "stopLossMultiplier",
                            bearCallSpreadConfig.getStopLossMultiplier().toString()));
            return true;
        }

        if (isDteExitTriggered(bearCallSpreadConfig.getMinDaysToExpiry())) {
            logDecision(
                    "EXIT_REASON",
                    "DTE exit triggered",
                    Map.of("minDaysToExpiry", bearCallSpreadConfig.getMinDaysToExpiry()));
            return true;
        }

        return false;
    }

    // ========================
    // ADJUSTMENT (roll sell leg up)
    // ========================

    /**
     * P&L-based roll-up adjustment for the sell leg:
     * When current P&L drops below -(rollThreshold), roll the sell leg up
     * by one strike interval to move away from the approaching underlying.
     */
    @Override
    protected void adjust(MarketSnapshot snapshot) {
        BigDecimal rollThreshold = bearCallSpreadConfig.getRollThreshold();
        if (rollThreshold == null) {
            return;
        }

        BigDecimal pnl = calculateTotalPnl();

        if (pnl.compareTo(rollThreshold.negate()) <= 0) {
            Position sellLeg = getSellLeg();
            if (sellLeg != null) {
                logDecision(
                        "ADJUST_EVAL",
                        "PnL below roll threshold, rolling sell leg up",
                        Map.of(
                                "pnl", pnl.toString(),
                                "threshold", rollThreshold.negate().toString(),
                                "sellLegSymbol", sellLeg.getTradingSymbol()));

                // #TODO Execute roll via multiLegExecutor
                recordAdjustment("ROLL_SELL_UP");
            }
        }
    }

    // ========================
    // MORPHING
    // ========================

    /**
     * A bear call spread can morph into:
     * - Bull Call Spread (flip direction by swapping buy/sell legs)
     * - Iron Condor (add a put spread below)
     */
    @Override
    public List<StrategyType> supportedMorphs() {
        return List.of(StrategyType.BULL_CALL_SPREAD, StrategyType.IRON_CONDOR);
    }

    // ========================
    // INTERNALS
    // ========================

    /** Finds the short call leg (quantity < 0, CE option). */
    private Position getSellLeg() {
        return positions.stream()
                .filter(p -> p.getQuantity() < 0
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
     * #TODO Replace with InstrumentService.resolveSymbol() for full Kite symbol
     */
    private String buildTradingSymbol(BigDecimal strike, String optionType) {
        return bearCallSpreadConfig.getUnderlying()
                + strike.stripTrailingZeros().toPlainString()
                + optionType;
    }
}
