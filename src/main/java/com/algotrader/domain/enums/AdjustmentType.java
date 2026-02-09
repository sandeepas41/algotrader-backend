package com.algotrader.domain.enums;

/**
 * What metric an adjustment rule monitors to decide when to adjust a position.
 * DELTA = position delta drift, PREMIUM = premium change from entry,
 * TIME = days to expiry, IV = implied volatility change.
 */
public enum AdjustmentType {
    DELTA,
    PREMIUM,
    TIME,
    IV
}
