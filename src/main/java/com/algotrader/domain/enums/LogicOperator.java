package com.algotrader.domain.enums;

/**
 * Logic operator for composite condition rules.
 *
 * <p>AND requires all child conditions to be true simultaneously.
 * OR requires at least one child condition to be true.
 * Used by CompositeConditionRule to combine multiple individual rules
 * into a single compound condition.
 */
public enum LogicOperator {
    /** All child conditions must be true. */
    AND,
    /** At least one child condition must be true. */
    OR
}
