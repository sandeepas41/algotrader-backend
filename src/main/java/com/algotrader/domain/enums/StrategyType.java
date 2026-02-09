package com.algotrader.domain.enums;

/**
 * Supported options strategy types. Each type has a dedicated implementation
 * extending BaseStrategy with its own entry/exit/adjustment logic.
 * CE_BUY/CE_SELL/PE_BUY/PE_SELL
 * are single-leg naked options (positional or scalping mode). LONG_STRADDLE buys
 * both ATM CE+PE. CUSTOM allows user-defined multi-leg configurations.
 */
public enum StrategyType {
    IRON_CONDOR,
    IRON_BUTTERFLY,
    STRADDLE,
    STRANGLE,
    BULL_CALL_SPREAD,
    BEAR_CALL_SPREAD,
    BULL_PUT_SPREAD,
    BEAR_PUT_SPREAD,
    CALENDAR_SPREAD,
    DIAGONAL_SPREAD,
    CE_BUY,
    CE_SELL,
    PE_BUY,
    PE_SELL,
    LONG_STRADDLE,
    CUSTOM
}
