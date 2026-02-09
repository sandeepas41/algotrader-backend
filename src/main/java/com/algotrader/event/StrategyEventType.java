package com.algotrader.event;

/**
 * Classifies the lifecycle transition that triggered a {@link StrategyEvent}.
 *
 * <p>These map to the strategy lifecycle: CREATED -> ARMED -> ACTIVE ->
 * PAUSED/MORPHING/CLOSING -> CLOSED. Some transitions (ENTRY_TRIGGERED,
 * EXIT_TRIGGERED, ADJUSTMENT_TRIGGERED) represent condition evaluations
 * that precede the actual state change.
 */
public enum StrategyEventType {

    /** Strategy was created (initial configuration saved). */
    CREATED,

    /** Strategy was armed (monitoring for entry conditions). */
    ARMED,

    /** Entry conditions were met — strategy is about to open positions. */
    ENTRY_TRIGGERED,

    /** Strategy is now active with open positions. */
    ACTIVE,

    /** An adjustment rule was triggered — positions are being adjusted. */
    ADJUSTMENT_TRIGGERED,

    /** Strategy was paused (manually or due to risk/session event). */
    PAUSED,

    /** Strategy was resumed from PAUSED state. */
    RESUMED,

    /** Exit conditions were met — strategy is closing positions. */
    EXIT_TRIGGERED,

    /** Strategy has been fully closed (all positions flat). */
    CLOSED
}
