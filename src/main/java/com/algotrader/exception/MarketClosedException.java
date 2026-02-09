package com.algotrader.exception;

/**
 * Thrown when an operation guarded by {@code @TradingHoursOnly} is invoked
 * outside of market hours.
 *
 * <p>This is a runtime exception because callers should check market status
 * proactively rather than relying on exception handling for flow control.
 */
public class MarketClosedException extends RuntimeException {

    public MarketClosedException(String message) {
        super(message);
    }
}
