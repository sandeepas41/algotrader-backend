package com.algotrader.domain.enums;

/**
 * Lifecycle status of a morph execution plan (WAL entry).
 *
 * <p>Transitions: PLANNED -> EXECUTING -> COMPLETED/PARTIALLY_DONE/FAILED/ROLLED_BACK.
 * On application restart, plans in EXECUTING state are candidates for recovery.
 */
public enum MorphPlanStatus {
    /** Plan created, not yet executed. Returned by morph preview. */
    PLANNED,
    /** Morph in progress -- legs being closed/opened. */
    EXECUTING,
    /** All steps completed successfully. */
    COMPLETED,
    /** Some steps completed, some failed. Requires manual intervention. */
    PARTIALLY_DONE,
    /** Morph failed entirely. Source strategy may be in MORPHING state. */
    FAILED,
    /** Morph was rolled back after partial failure. */
    ROLLED_BACK
}
