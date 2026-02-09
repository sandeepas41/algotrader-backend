package com.algotrader.domain.enums;

/**
 * Supported options strategy types. Each type has a dedicated implementation
 * extending BaseStrategy with its own entry/exit/adjustment logic.
 * SCALPING is tick-level with point-based exits. CUSTOM allows user-defined
 * multi-leg configurations.
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
    SCALPING,
    CUSTOM
}
