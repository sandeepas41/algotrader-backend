package com.algotrader.domain.enums;

/**
 * Lifecycle status of a trading strategy.
 * Transitions: CREATED → ARMED → ACTIVE → PAUSED/MORPHING/CLOSING → CLOSED.
 * MORPHING means the strategy is being transformed into a different type
 * (e.g., straddle → strangle) while preserving existing legs.
 */
public enum StrategyStatus {
    CREATED,
    ARMED,
    ACTIVE,
    PAUSED,
    MORPHING,
    CLOSING,
    CLOSED
}
