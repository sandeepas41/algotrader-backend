package com.algotrader.domain.enums;

/**
 * Status of a single execution run within a strategy.
 * A strategy can have multiple runs over its lifetime (e.g., re-armed after closing).
 * ABORTED means the run was forcefully terminated (kill switch, broker disconnect, error).
 */
public enum StrategyRunStatus {
    ACTIVE,
    COMPLETED,
    ABORTED
}
