package com.algotrader.event;

/**
 * Classifies the type of position change that triggered a {@link PositionEvent}.
 *
 * <p>Listeners use this to distinguish between new positions (requiring strategy
 * tracking setup) and P&L updates (requiring risk checks).
 */
public enum PositionEventType {

    /** A new position was opened (first fill for this instrument/strategy). */
    OPENED,

    /** Position P&L or Greeks were updated (typically from a tick). */
    UPDATED,

    /** Position size was increased (additional buy/sell in the same direction). */
    INCREASED,

    /** Position size was reduced (partial close). */
    REDUCED,

    /** Position was fully closed (quantity reached zero). */
    CLOSED
}
