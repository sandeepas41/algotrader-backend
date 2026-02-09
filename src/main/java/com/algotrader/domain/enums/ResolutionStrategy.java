package com.algotrader.domain.enums;

/**
 * Determines how a position mismatch should be resolved during reconciliation.
 *
 * <p>AUTO_SYNC silently updates local state from broker (default for most mismatches).
 * PAUSE_STRATEGY also syncs but pauses the owning strategy to prevent decisions
 * on stale state. ALERT_ONLY takes no corrective action and defers to manual review.
 */
public enum ResolutionStrategy {
    AUTO_SYNC,
    ALERT_ONLY,
    PAUSE_STRATEGY
}
