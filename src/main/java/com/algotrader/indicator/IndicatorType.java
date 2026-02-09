package com.algotrader.indicator;

/**
 * Supported technical indicator types for the IndicatorService.
 *
 * <p>Each type maps to one or more ta4j indicator instances. Multi-output indicators
 * (Bollinger, MACD, SuperTrend, Stochastic) produce multiple keyed values per bar.
 *
 * <p>LTP is a pseudo-indicator representing the raw last traded price, useful for
 * condition rules that need to compare against price directly without ta4j processing.
 */
public enum IndicatorType {
    RSI,
    EMA,
    SMA,
    MACD,
    BOLLINGER,
    SUPERTREND,
    VWAP,
    ATR,
    STOCHASTIC,
    LTP
}
