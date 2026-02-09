package com.algotrader.event;

/**
 * Classifies the type of session lifecycle change that triggered a {@link SessionEvent}.
 *
 * <p>Session events are critical for ensuring strategies do not operate with
 * an expired or invalid broker session. The StrategyEngine pauses all strategies
 * on SESSION_EXPIRED and allows resume on SESSION_RECONNECTED.
 */
public enum SessionEventType {

    /** New session established after OAuth callback. */
    SESSION_CREATED,

    /** Periodic health check confirmed session is valid. */
    SESSION_VALIDATED,

    /** Session approaching expiry or health check failures detected. */
    SESSION_EXPIRY_WARNING,

    /** Session is no longer valid (expired or health check failures exceeded threshold). */
    SESSION_EXPIRED,

    /** Session re-established after expiry/disconnect via auto-reauth. */
    SESSION_RECONNECTED,

    /** Session invalidated by another login or broker-side revocation (403 from Kite). */
    SESSION_INVALIDATED
}
