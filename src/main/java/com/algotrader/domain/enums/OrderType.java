package com.algotrader.domain.enums;

/**
 * Order execution type. Maps to Kite API's order_type field.
 * SL = stop-loss limit (requires both price and trigger_price).
 * SL_M = stop-loss market (requires only trigger_price).
 */
public enum OrderType {
    MARKET,
    LIMIT,
    SL,
    SL_M
}
