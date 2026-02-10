package com.algotrader.domain.enums;

/**
 * Expiry selection strategy for watchlist subscriptions.
 *
 * <p>When auto-subscribing instruments on startup, this determines
 * which expiry to use for option chain subscriptions.
 */
public enum ExpiryType {
    /** Select the nearest weekly expiry (closest upcoming expiry date). */
    NEAREST_WEEKLY,

    /** Select the nearest monthly expiry (last Thursday of the month). */
    NEAREST_MONTHLY
}
