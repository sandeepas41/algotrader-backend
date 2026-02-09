package com.algotrader.domain.enums;

/**
 * Comparison operators for condition rule evaluation.
 *
 * <p>Simple operators (GT, LT, GTE, LTE) compare the current indicator value
 * against a threshold. Crossing operators (CROSSES_ABOVE, CROSSES_BELOW) detect
 * transitions between ticks — they require tracking the previous indicator value
 * and fire only when the value crosses the threshold from one side to the other.
 *
 * <p>BETWEEN and OUTSIDE use thresholdValue as the lower bound and
 * secondaryThreshold as the upper bound.
 */
public enum ConditionOperator {
    /** Current value > threshold. */
    GT,
    /** Current value < threshold. */
    LT,
    /** Current value >= threshold. */
    GTE,
    /** Current value <= threshold. */
    LTE,
    /** Previous value < threshold AND current value >= threshold. */
    CROSSES_ABOVE,
    /** Previous value > threshold AND current value <= threshold. */
    CROSSES_BELOW,
    /** Current value between threshold (low) and secondaryThreshold (high). */
    BETWEEN,
    /** Current value outside threshold (low) and secondaryThreshold (high). */
    OUTSIDE
}
