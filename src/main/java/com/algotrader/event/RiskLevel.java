package com.algotrader.event;

/**
 * Severity level for a {@link RiskEvent}.
 *
 * <p>INFO is for approaching thresholds, WARNING for breaches that require attention,
 * and CRITICAL for conditions that trigger automatic protective actions (like
 * kill switch activation or strategy pausing).
 */
public enum RiskLevel {

    /** Informational — threshold approaching but no action required yet. */
    INFO,

    /** Warning — threshold breached, trader should take action. */
    WARNING,

    /** Critical — automatic protective action triggered (kill switch, strategy pause). */
    CRITICAL
}
