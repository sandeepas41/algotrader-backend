package com.algotrader.session;

/**
 * Describes the current system degradation level based on session state.
 *
 * <p>Used by SessionGuard to determine what operations are allowed when
 * the Kite session is in various states. Components check the degradation
 * level to decide whether to proceed with live broker calls or fall back
 * to cached/simulated data.
 */
public enum DegradationLevel {

    /** Full functionality available -- session is active and verified. */
    NONE,

    /** Session expiring soon -- all features work but user should re-authenticate. */
    WARNING,

    /** Authentication in progress -- limited features, live orders may be delayed. */
    PARTIAL,

    /** Session expired/disconnected -- live trading blocked, paper trading works, monitoring uses cached data. */
    DEGRADED
}
