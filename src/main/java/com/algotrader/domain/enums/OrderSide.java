package com.algotrader.domain.enums;

/** Buy or sell side of an order. Maps to Kite API's transaction_type field. */
public enum OrderSide {
    BUY,
    SELL;

    /** Returns the opposite side: BUY -> SELL, SELL -> BUY. Used for rollback orders. */
    public OrderSide opposite() {
        return this == BUY ? SELL : BUY;
    }
}
