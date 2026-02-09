package com.algotrader.domain.enums;

/**
 * Comparison operator used by adjustment rules and condition rules
 * to evaluate whether a threshold has been breached.
 * BETWEEN requires both threshold and threshold2 values.
 */
public enum TriggerCondition {
    GT,
    LT,
    EQ,
    GTE,
    LTE,
    BETWEEN
}
