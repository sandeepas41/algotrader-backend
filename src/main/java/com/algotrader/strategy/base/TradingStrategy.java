package com.algotrader.strategy.base;

import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Position;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Contract for all trading strategies in the system.
 *
 * <p>Every strategy type (straddle, iron condor, scalping, etc.) implements this interface
 * via {@link BaseStrategy}. The StrategyEngine uses this interface to drive evaluation,
 * lifecycle transitions, and position tracking without knowing the specific strategy type.
 *
 * <p>Strategies are self-contained: each implementation knows its own entry/exit/adjustment
 * logic, monitoring interval, stale data threshold, and supported morphs. The StrategyEngine
 * simply calls {@link #evaluate(MarketSnapshot)} on the configured interval and the strategy
 * decides what to do.
 */
public interface TradingStrategy {

    // ---- Identity ----

    String getId();

    String getName();

    StrategyType getType();

    StrategyStatus getStatus();

    // ---- Configuration ----

    /** How often this strategy should be evaluated. Positional: 5 min, Scalping: Duration.ZERO (every tick). */
    Duration getMonitoringInterval();

    /** Max age of tick data before it's considered stale. Default 5s, scalping 2s. */
    Duration getStaleDataThreshold();

    /** Cooldown between adjustments. Positional: 5 min, Scalping: 10-30s. */
    Duration getAdjustmentCooldown();

    /** The root underlying symbol (e.g., "NIFTY", "BANKNIFTY"). */
    String getUnderlying();

    // ---- Lifecycle ----

    void arm();

    void pause();

    void resume();

    void initiateClose();

    // ---- Evaluation ----

    /**
     * Core evaluation loop. Called by the StrategyEngine on tick or at the monitoring interval.
     * Internally enforces interval timing, stale data guards, and delegates to entry/exit/adjust.
     */
    void evaluate(MarketSnapshot snapshot);

    // ---- Position tracking ----

    List<Position> getPositions();

    void updatePosition(Position position);

    void addPosition(Position position);

    void removePosition(String positionId);

    // ---- P&L ----

    BigDecimal calculateTotalPnl();

    BigDecimal getEntryPremium();

    // ---- Timing ----

    LocalDateTime getLastEvaluationTime();

    LocalDateTime getEntryTime();

    // ---- Morphing ----

    List<StrategyType> supportedMorphs();

    // ---- Auto-pause thresholds ----

    /** P&L threshold below which the strategy auto-pauses. Null = disabled. */
    BigDecimal getAutoPausePnlThreshold();

    /** Delta threshold above which the strategy auto-pauses. Null = disabled. */
    BigDecimal getAutoPauseDeltaThreshold();
}
