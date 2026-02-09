package com.algotrader.domain.enums;

/**
 * Lifecycle status of a condition rule.
 *
 * <p>ACTIVE rules are loaded into the ConditionEngine's in-memory cache and
 * evaluated on every tick or interval. PAUSED rules are excluded from evaluation
 * but retained in the database. TRIGGERED means the rule has reached its max
 * trigger count and will not fire again. EXPIRED means the rule's validity
 * window has passed.
 */
public enum ConditionRuleStatus {
    /** Rule is being evaluated. */
    ACTIVE,
    /** Rule is temporarily disabled by user. */
    PAUSED,
    /** Rule has reached its max trigger count. */
    TRIGGERED,
    /** Rule is past its validity window. */
    EXPIRED
}
