package com.algotrader.domain.enums;

/**
 * Lifecycle status of an order.
 * PENDING is our internal pre-submission state; remaining states map to Kite API statuses.
 * TRIGGER_PENDING means an SL order is waiting for its trigger price to be hit.
 */
public enum OrderStatus {
    PENDING,
    OPEN,
    COMPLETE,
    PARTIAL,
    CANCELLED,
    REJECTED,
    TRIGGER_PENDING
}
