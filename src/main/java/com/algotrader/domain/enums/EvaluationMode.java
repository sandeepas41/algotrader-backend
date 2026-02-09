package com.algotrader.domain.enums;

/**
 * Determines how frequently a condition rule is evaluated.
 *
 * <p>TICK mode evaluates on every incoming market tick — used for scalping
 * strategies that need sub-second reaction times. Interval modes evaluate
 * on a scheduled basis to reduce computation for positional strategies.
 *
 * <p>The ConditionEngine routes tick-mode rules through the TickEvent listener
 * and interval-mode rules through a @Scheduled method.
 */
public enum EvaluationMode {
    /** Evaluate on every tick (for scalping strategies). */
    TICK,
    /** Evaluate every 1 minute. */
    INTERVAL_1M,
    /** Evaluate every 5 minutes (default for positional). */
    INTERVAL_5M,
    /** Evaluate every 15 minutes. */
    INTERVAL_15M
}
