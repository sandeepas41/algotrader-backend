package com.algotrader.domain.enums;

/**
 * Severity level for alerts in the notification system.
 *
 * <p>Determines default channel routing:
 * <ul>
 *   <li>CRITICAL: All channels (in-app + Telegram + Email)</li>
 *   <li>WARNING: Telegram + in-app</li>
 *   <li>INFO: In-app only</li>
 * </ul>
 *
 * <p>Ordinal ordering is used by TelegramNotifier's priority queue
 * so that CRITICAL messages are sent first when rate-limited.
 */
public enum AlertSeverity {

    /** Requires immediate attention and automatic protective action. */
    CRITICAL,

    /** Requires trader attention but no automatic action. */
    WARNING,

    /** Informational — no action required. */
    INFO
}
