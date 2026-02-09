package com.algotrader.domain.enums;

/**
 * Priority level for instrument WebSocket subscriptions.
 *
 * <p>When the 3000-instrument limit is reached, lower-priority subscriptions
 * are evicted to make room for higher-priority ones. Order from lowest to highest:
 * MANUAL (watchlist) < CONDITION (monitoring triggers) < STRATEGY (active trading).
 */
public enum SubscriptionPriority {
    /** Lowest priority: manual watchlist subscriptions from the UI. */
    MANUAL,

    /** Medium priority: condition monitoring (alerts, triggers). */
    CONDITION,

    /** Highest priority: instruments actively traded by a running strategy. */
    STRATEGY
}
