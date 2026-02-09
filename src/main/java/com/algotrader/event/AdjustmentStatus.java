package com.algotrader.event;

/**
 * Lifecycle status of an adjustment operation triggered by an {@link AdjustmentEvent}.
 *
 * <p>Adjustments progress: PENDING -> EXECUTING -> COMPLETED/FAILED.
 * Multiple events may be published for the same adjustment as it progresses
 * through these states.
 */
public enum AdjustmentStatus {

    /** Adjustment has been triggered but not yet started executing. */
    PENDING,

    /** Adjustment orders are being placed/executed. */
    EXECUTING,

    /** Adjustment completed successfully — all orders filled. */
    COMPLETED,

    /** Adjustment failed — one or more orders rejected or timed out. */
    FAILED
}
