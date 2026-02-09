package com.algotrader.domain.enums;

/**
 * Severity level for decision log entries, controlling which entries are
 * persisted to H2 and how they appear in the frontend decision log feed.
 *
 * <ul>
 *   <li>DEBUG -- Routine evaluations (strategy checked but no action). Only persisted
 *       when persist-debug toggle is enabled at runtime.</li>
 *   <li>INFO -- Notable events: entries, exits, successful adjustments.</li>
 *   <li>WARNING -- Risk warnings, stale data detection, near-limit conditions.</li>
 *   <li>CRITICAL -- Kill switch activation, forced exits, unrecoverable failures.</li>
 * </ul>
 */
public enum DecisionSeverity {
    DEBUG,
    INFO,
    WARNING,
    CRITICAL
}
