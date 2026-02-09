package com.algotrader.domain.enums;

/**
 * Tracks the state of an order modification (amendment) request through
 * its lifecycle from initial request through broker confirmation/rejection.
 *
 * <p>State transitions:
 * <pre>
 * NONE → MODIFY_REQUESTED → MODIFY_SENT → MODIFY_CONFIRMED | MODIFY_REJECTED
 * </pre>
 *
 * <p>Only orders in NONE state can accept new modification requests. An order
 * stuck in MODIFY_SENT can be re-modified only after the broker confirms or
 * the amendment times out (handled by OrderTimeoutMonitor).
 */
public enum AmendmentStatus {

    /** No modification in progress. Default state. */
    NONE,

    /** Modification validated locally, ready to send to broker. */
    MODIFY_REQUESTED,

    /** Modification sent to Kite API, waiting for confirmation. */
    MODIFY_SENT,

    /** Kite confirmed the modification. Order reverts to NONE for future modifications. */
    MODIFY_CONFIRMED,

    /** Kite rejected the modification. Original order parameters remain. */
    MODIFY_REJECTED
}
