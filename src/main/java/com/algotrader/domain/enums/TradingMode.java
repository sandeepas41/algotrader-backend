package com.algotrader.domain.enums;

/**
 * Execution mode for a strategy.
 * LIVE sends real orders to Kite. PAPER simulates execution.
 * HYBRID uses real market data but simulated order execution.
 */
public enum TradingMode {
    LIVE,
    PAPER,
    HYBRID
}
