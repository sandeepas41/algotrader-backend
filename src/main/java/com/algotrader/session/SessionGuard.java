package com.algotrader.session;

import com.algotrader.exception.SessionExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Guards live trading operations by checking session state before execution.
 *
 * <p>Components that need a valid Kite session call {@link #requireActiveSession()}
 * before performing broker operations. If the session is not active, a
 * {@link SessionExpiredException} is thrown.
 *
 * <p>Also provides {@link #getDegradationLevel()} for components that need
 * to adapt their behavior based on session state (e.g., showing cached data
 * instead of live data, or allowing paper trading but blocking live orders).
 *
 * <p>What works when session is expired:
 * <ul>
 *   <li>Paper trading -- uses simulated broker gateway</li>
 *   <li>Position monitoring -- shows last known positions from Redis</li>
 *   <li>Strategy configuration -- can create/edit, cannot arm</li>
 *   <li>P&L reports -- uses persisted data from H2</li>
 * </ul>
 *
 * <p>What is blocked when session is expired:
 * <ul>
 *   <li>Live order placement</li>
 *   <li>Live option chain data</li>
 * </ul>
 */
@Component
public class SessionGuard {

    private static final Logger log = LoggerFactory.getLogger(SessionGuard.class);

    private final SessionHealthService sessionHealthService;

    public SessionGuard(SessionHealthService sessionHealthService) {
        this.sessionHealthService = sessionHealthService;
    }

    /**
     * Checks if the session is active. Throws if not.
     * Call before any operation that requires a valid Kite session.
     *
     * @throws SessionExpiredException if the session is not active
     */
    public void requireActiveSession() {
        if (!sessionHealthService.isSessionActive()) {
            SessionState state = sessionHealthService.getState();
            throw new SessionExpiredException(
                    "Kite session is not active. Current state: " + state + ". Please re-authenticate.");
        }
    }

    /**
     * Returns the current degradation level based on session state.
     */
    public DegradationLevel getDegradationLevel() {
        return switch (sessionHealthService.getState()) {
            case ACTIVE, CONNECTED -> DegradationLevel.NONE;
            case EXPIRY_WARNING -> DegradationLevel.WARNING;
            case AUTHENTICATING -> DegradationLevel.PARTIAL;
            case EXPIRED, DISCONNECTED -> DegradationLevel.DEGRADED;
        };
    }

    /**
     * Returns true if the session is in a state that allows live broker operations.
     */
    public boolean isLiveTradingAllowed() {
        return sessionHealthService.isSessionActive();
    }
}
