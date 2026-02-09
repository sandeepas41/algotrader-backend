package com.algotrader.domain.enums;

/**
 * Determines how the system calculates the number of lots for a new trade.
 *
 * <p>Each type maps to a {@link com.algotrader.margin.PositionSizer} implementation
 * that is resolved by {@link com.algotrader.margin.PositionSizerFactory}. Strategy
 * configs reference this enum to declare their preferred sizing approach.
 */
public enum PositionSizingType {

    /** Trade a fixed number of lots regardless of account size or risk. */
    FIXED_LOTS,

    /** Allocate a percentage of total capital, then convert to lots via margin per lot. */
    PERCENTAGE_OF_CAPITAL,

    /** Size based on maximum acceptable loss per trade (risk-per-trade model). */
    RISK_BASED
}
