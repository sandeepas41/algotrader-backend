package com.algotrader.strategy.impl;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.oms.OrderRequest;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.MarketSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Long straddle strategy: BUY ATM Call + BUY ATM Put on the same underlying and expiry.
 *
 * <p><b>Market view:</b> Volatile / breakout. Profits from large moves in either direction
 * (high gamma). Loses from time decay (negative theta). Opposite of the short straddle --
 * limited risk (premium paid), unlimited reward on both sides.
 *
 * <p><b>Dual operating modes:</b>
 * <ul>
 *   <li><b>Positional</b> (scalpingMode=false): 5-min evaluation, %-based exits
 *       (targetPercent, stopLossMultiplier, minDaysToExpiry)</li>
 *   <li><b>Scalping</b> (scalpingMode=true): tick-level evaluation, point-based exits
 *       (targetPoints, stopLossPoints, maxHoldDuration)</li>
 * </ul>
 *
 * <p><b>Entry conditions:</b>
 * <ul>
 *   <li>autoEntry must be enabled</li>
 *   <li>Within configured entry time window</li>
 *   <li>ATM IV >= minIV (if configured)</li>
 * </ul>
 *
 * <p><b>Legs:</b> 2 (BUY ATM CE + BUY ATM PE).
 *
 * <p><b>Adjustments:</b> None. Long premium decays; exit, don't adjust.
 *
 * <p><b>Morphing:</b> None.
 */
public class LongStraddleStrategy extends BaseStrategy {

    private static final Logger log = LoggerFactory.getLogger(LongStraddleStrategy.class);

    /** Positional mode evaluates every 5 minutes. */
    private static final Duration POSITIONAL_INTERVAL = Duration.ofMinutes(5);

    /** Scalping mode uses a tighter 2-second stale data threshold. */
    private static final Duration SCALPING_STALE_THRESHOLD = Duration.ofSeconds(2);

    private final LongStraddleConfig longStraddleConfig;

    public LongStraddleStrategy(String id, String name, LongStraddleConfig longStraddleConfig) {
        super(id, name, longStraddleConfig);
        this.longStraddleConfig = longStraddleConfig;
    }

    // ========================
    // IDENTITY
    // ========================

    @Override
    public StrategyType getType() {
        return StrategyType.LONG_STRADDLE;
    }

    /** Tick-level in scalping mode; 5-min in positional mode. */
    @Override
    public Duration getMonitoringInterval() {
        return longStraddleConfig.isScalpingMode() ? Duration.ZERO : POSITIONAL_INTERVAL;
    }

    /** 2s stale threshold in scalping mode; default 5s in positional mode. */
    @Override
    public Duration getStaleDataThreshold() {
        return longStraddleConfig.isScalpingMode() ? SCALPING_STALE_THRESHOLD : super.getStaleDataThreshold();
    }

    // ========================
    // ENTRY
    // ========================

    /**
     * Entry requires:
     * 1. autoEntry enabled
     * 2. Within configured entry window
     * 3. ATM IV >= minIV (if minIV is configured)
     */
    @Override
    protected boolean shouldEnter(MarketSnapshot snapshot) {
        if (!longStraddleConfig.isAutoEntry()) {
            return false;
        }

        if (!isWithinEntryWindow()) {
            return false;
        }

        // IV filter (optional)
        BigDecimal minIV = longStraddleConfig.getMinIV();
        BigDecimal atmIV = snapshot.getAtmIV();
        if (minIV != null && (atmIV == null || atmIV.compareTo(minIV) < 0)) {
            return false;
        }

        return true;
    }

    /**
     * Builds 2 buy-side MARKET orders at the ATM strike: one CE, one PE.
     * Opposite of the short straddle which SELLs both legs.
     */
    @Override
    protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
        BigDecimal atmStrike = roundToStrike(snapshot.getSpotPrice());

        OrderRequest buyCE = OrderRequest.builder()
                .tradingSymbol(buildTradingSymbol(atmStrike, "CE"))
                .side(OrderSide.BUY)
                .type(OrderType.MARKET)
                .quantity(1)
                .build();

        OrderRequest buyPE = OrderRequest.builder()
                .tradingSymbol(buildTradingSymbol(atmStrike, "PE"))
                .side(OrderSide.BUY)
                .type(OrderType.MARKET)
                .quantity(1)
                .build();

        return List.of(buyCE, buyPE);
    }

    // ========================
    // EXIT
    // ========================

    /**
     * Dual-mode exit logic:
     * - Scalping: point-based (targetPoints, stopLossPoints, maxHoldDuration)
     * - Positional: %-based (targetPercent, stopLossMultiplier, minDaysToExpiry)
     */
    @Override
    protected boolean shouldExit(MarketSnapshot snapshot) {
        if (positions.isEmpty()) {
            return false;
        }

        if (longStraddleConfig.isScalpingMode()) {
            return shouldExitScalping();
        } else {
            return shouldExitPositional();
        }
    }

    private boolean shouldExitScalping() {
        BigDecimal pnl = calculateTotalPnl();

        // Target hit
        BigDecimal target = longStraddleConfig.getTargetPoints();
        if (target != null && pnl.compareTo(target) >= 0) {
            logDecision(
                    "EXIT_REASON",
                    "Target points reached",
                    Map.of("pnl", pnl.toString(), "targetPoints", target.toString()));
            return true;
        }

        // Stop loss hit
        BigDecimal stopLoss = longStraddleConfig.getStopLossPoints();
        if (stopLoss != null && pnl.compareTo(stopLoss.negate()) <= 0) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss points hit",
                    Map.of("pnl", pnl.toString(), "stopLossPoints", stopLoss.toString()));
            return true;
        }

        // Max hold duration exceeded
        Duration maxHold = longStraddleConfig.getMaxHoldDuration();
        if (maxHold != null && entryTime != null) {
            Duration held = Duration.between(entryTime, LocalDateTime.now());
            if (held.compareTo(maxHold) > 0) {
                logDecision(
                        "EXIT_REASON",
                        "Max hold duration exceeded",
                        Map.of(
                                "heldSeconds", String.valueOf(held.toSeconds()),
                                "maxSeconds", String.valueOf(maxHold.toSeconds())));
                return true;
            }
        }

        return false;
    }

    private boolean shouldExitPositional() {
        // Target: P&L >= entryPremium * targetPercent
        if (isTargetReached(longStraddleConfig.getTargetPercent())) {
            logDecision(
                    "EXIT_REASON",
                    "Target profit reached",
                    Map.of(
                            "targetPercent",
                            longStraddleConfig.getTargetPercent().toString()));
            return true;
        }

        // Stop loss: P&L <= -(entryPremium * stopLossMultiplier)
        if (isStopLossHit(longStraddleConfig.getStopLossMultiplier())) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss hit",
                    Map.of(
                            "stopLossMultiplier",
                            longStraddleConfig.getStopLossMultiplier().toString()));
            return true;
        }

        // DTE exit
        if (isDteExitTriggered(longStraddleConfig.getMinDaysToExpiry())) {
            logDecision(
                    "EXIT_REASON",
                    "DTE exit triggered",
                    Map.of("minDaysToExpiry", longStraddleConfig.getMinDaysToExpiry()));
            return true;
        }

        return false;
    }

    // ========================
    // ADJUSTMENT
    // ========================

    /** Long straddles don't adjust -- long premium decays; exit, don't adjust. */
    @Override
    protected void adjust(MarketSnapshot snapshot) {
        // No-op: exit rather than adjust
    }

    // ========================
    // MORPHING
    // ========================

    /** Long straddles don't morph. */
    @Override
    public List<StrategyType> supportedMorphs() {
        return List.of();
    }

    // ========================
    // INTERNALS
    // ========================

    /**
     * Builds a placeholder trading symbol for the order.
     * #TODO Task 8.3: Replace with InstrumentService.resolveSymbol() for full Kite symbol
     */
    private String buildTradingSymbol(BigDecimal strike, String optionType) {
        return longStraddleConfig.getUnderlying() + strike.stripTrailingZeros().toPlainString() + optionType;
    }
}
