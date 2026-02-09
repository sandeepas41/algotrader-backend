package com.algotrader.domain.vo;

import lombok.Value;

/**
 * Immutable value object representing a tradeable quantity with lot size awareness.
 * NSE F&O instruments trade in fixed lot sizes (e.g., NIFTY = 75, BANKNIFTY = 15).
 * This VO ensures quantity is always expressible in whole lots.
 */
@Value
public class Quantity {

    int value;
    int lotSize;

    public int getLots() {
        return value / lotSize;
    }

    public static Quantity ofLots(int lots, int lotSize) {
        return new Quantity(lots * lotSize, lotSize);
    }

    public static Quantity of(int value, int lotSize) {
        return new Quantity(value, lotSize);
    }
}
