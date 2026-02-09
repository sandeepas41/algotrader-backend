package com.algotrader.event;

/**
 * Classifies the type of order state change that triggered an {@link OrderEvent}.
 *
 * <p>Listeners can filter on event type to handle only relevant transitions.
 * For example, PositionService only cares about FILLED events, while the
 * AlertService may want notifications for REJECTED and CANCELLED.
 */
public enum OrderEventType {

    /** Order successfully placed with the broker. */
    PLACED,

    /** Order parameters modified (price, quantity, trigger). */
    MODIFIED,

    /** Order cancelled (by user, strategy, or kill switch). */
    CANCELLED,

    /** SL/SL-M trigger price hit — order now active in the market. */
    TRIGGERED,

    /** Order partially executed — filledQuantity increased but not yet equal to quantity. */
    PARTIALLY_FILLED,

    /** Order fully executed — all requested quantity has been filled. */
    FILLED,

    /** Order rejected by the exchange or broker. */
    REJECTED
}
