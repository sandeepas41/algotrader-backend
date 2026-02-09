package com.algotrader.domain.enums;

/**
 * How a strategy leg's strike price is selected.
 * ATM/OTM/ITM are relative to the current spot price with an offset
 * (e.g., OTM+2 = 2 strikes out-of-the-money). FIXED uses an absolute strike.
 */
public enum StrikeType {
    ATM,
    OTM,
    ITM,
    FIXED
}
