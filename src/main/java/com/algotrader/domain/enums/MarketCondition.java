package com.algotrader.domain.enums;

/**
 * Market condition tags for trade journal entries.
 * Helps correlate strategy performance with market environment.
 */
public enum MarketCondition {
    TRENDING,
    RANGING,
    VOLATILE,
    LOW_VOLATILITY,
    EXPIRY_DAY,
    GAP_UP,
    GAP_DOWN,
    NEWS_DRIVEN
}
