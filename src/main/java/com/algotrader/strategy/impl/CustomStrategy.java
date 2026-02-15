package com.algotrader.strategy.impl;

import com.algotrader.domain.enums.StrategyType;
import com.algotrader.oms.OrderRequest;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.MarketSnapshot;
import com.algotrader.strategy.base.PositionalStrategyConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic multi-leg strategy with user-defined legs and standard positional exit logic.
 *
 * <p><b>Market view:</b> User-defined. CUSTOM accepts any combination of CE/PE buy/sell legs
 * at arbitrary strikes. The system does not validate leg coherence — the user has full control.
 *
 * <p><b>Entry:</b> Always immediate. Legs are defined by the user (from broker positions or
 * built from scratch). {@code shouldEnter()} returns true on first tick to support the
 * non-immediate entry path, but the typical flow uses {@code immediateEntry=true} with
 * FE-sent FIXED strike leg configs.
 *
 * <p><b>Exit:</b> Standard positional: target profit (% of entry premium), stop-loss
 * (multiplier of entry premium), and DTE-based exit. Same logic as Iron Condor, Straddle, etc.
 *
 * <p><b>Adjustments:</b> None. No-op.
 *
 * <p><b>Morphing:</b> None.
 */
public class CustomStrategy extends BaseStrategy {

    private static final Logger log = LoggerFactory.getLogger(CustomStrategy.class);

    /** Positional mode: evaluate every 5 minutes. */
    private static final Duration POSITIONAL_INTERVAL = Duration.ofMinutes(5);

    private final PositionalStrategyConfig positionalStrategyConfig;

    public CustomStrategy(String id, String name, PositionalStrategyConfig positionalStrategyConfig) {
        super(id, name, positionalStrategyConfig);
        this.positionalStrategyConfig = positionalStrategyConfig;
    }

    // ========================
    // IDENTITY
    // ========================

    @Override
    public StrategyType getType() {
        return StrategyType.CUSTOM;
    }

    @Override
    public Duration getMonitoringInterval() {
        return POSITIONAL_INTERVAL;
    }

    // ========================
    // ENTRY
    // ========================

    /**
     * CUSTOM strategies always enter immediately. This returns true on first evaluation
     * to support the non-immediate entry path (e.g., when armed without immediateEntry flag).
     * In typical usage, entry happens via executeImmediateEntry() from BaseStrategy.
     */
    @Override
    protected boolean shouldEnter(MarketSnapshot snapshot) {
        return true;
    }

    /**
     * Builds entry orders from the FE-sent leg configs.
     * In practice, CUSTOM strategies use immediateEntry which calls executeImmediateEntry()
     * directly — this method is the fallback for the non-immediate path.
     */
    @Override
    protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
        // Immediate entry handles order building from legConfigs.
        // This fallback returns empty — CUSTOM should always use immediateEntry.
        log.warn("buildEntryOrders called for CUSTOM strategy {} — expected immediateEntry path", getId());
        return List.of();
    }

    // ========================
    // EXIT
    // ========================

    /**
     * Standard positional exit: target, stop-loss, DTE.
     * Same logic used by Straddle, Iron Condor, and other positional strategies.
     */
    @Override
    protected boolean shouldExit(MarketSnapshot snapshot) {
        if (positions.isEmpty()) {
            return false;
        }

        if (isTargetReached(positionalStrategyConfig.getTargetPercent())) {
            logDecision(
                    "EXIT_REASON",
                    "Target profit reached",
                    Map.of(
                            "targetPercent",
                            positionalStrategyConfig.getTargetPercent().toString()));
            return true;
        }

        if (isStopLossHit(positionalStrategyConfig.getStopLossMultiplier())) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss hit",
                    Map.of(
                            "stopLossMultiplier",
                            positionalStrategyConfig.getStopLossMultiplier().toString()));
            return true;
        }

        if (isDteExitTriggered(positionalStrategyConfig.getMinDaysToExpiry())) {
            logDecision(
                    "EXIT_REASON",
                    "DTE exit triggered",
                    Map.of("minDaysToExpiry", positionalStrategyConfig.getMinDaysToExpiry()));
            return true;
        }

        return false;
    }

    // ========================
    // ADJUSTMENT
    // ========================

    /** CUSTOM strategies don't auto-adjust — user manages position changes manually. */
    @Override
    protected void adjust(MarketSnapshot snapshot) {
        // No-op: arbitrary leg structures have no universal adjustment logic
    }

    // ========================
    // MORPHING
    // ========================

    /** CUSTOM strategies don't morph. */
    @Override
    public List<StrategyType> supportedMorphs() {
        return List.of();
    }
}
