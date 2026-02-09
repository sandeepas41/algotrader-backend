package com.algotrader.session;

import com.algotrader.broker.KiteAuthService;
import com.algotrader.event.SessionEvent;
import com.algotrader.event.SessionEventType;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodically validates the Kite session via a lightweight API call (getProfile).
 *
 * <p>Polls every 5 minutes when the session is in an active state (CONNECTED, ACTIVE,
 * or EXPIRY_WARNING). After 3 consecutive health check failures, transitions the
 * session to EXPIRED and publishes a {@link SessionEvent}.
 *
 * <p>This service owns the canonical {@link SessionState} for the entire application.
 * Other components (SessionGuard, SessionExpiryService) read state from here. State
 * transitions publish {@link SessionEvent}s so that listeners (StrategyEngine, AlertService,
 * WebSocketHandler) can react accordingly.
 *
 * <p>Session persistence to H2/Redis is handled by KiteAuthService. This service
 * only tracks the live session state in memory via AtomicReference.
 */
@Service
public class SessionHealthService {

    private static final Logger log = LoggerFactory.getLogger(SessionHealthService.class);

    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final KiteAuthService kiteAuthService;
    private final ApplicationEventPublisher applicationEventPublisher;

    private final AtomicReference<SessionState> currentState = new AtomicReference<>(SessionState.DISCONNECTED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public SessionHealthService(KiteAuthService kiteAuthService, ApplicationEventPublisher applicationEventPublisher) {
        this.kiteAuthService = kiteAuthService;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Health check every 5 minutes via getProfile() -- a lightweight read-only Kite API call.
     * Skipped when session is DISCONNECTED or EXPIRED (no point in checking a dead session).
     */
    @Scheduled(fixedRate = 300_000)
    public void checkSessionHealth() {
        checkSessionHealth(currentState.get());
    }

    /**
     * Testable version: performs the health check given a state.
     */
    public void checkSessionHealth(SessionState state) {
        if (state == SessionState.DISCONNECTED || state == SessionState.EXPIRED) {
            return;
        }

        try {
            // Lightweight API call to validate the token is still valid
            kiteAuthService.getKiteConnect().getProfile();

            consecutiveFailures.set(0);

            // Promote CONNECTED -> ACTIVE on first successful health check
            if (currentState.get() == SessionState.CONNECTED) {
                SessionState previous = currentState.getAndSet(SessionState.ACTIVE);
                publishTransition(
                        SessionEventType.SESSION_VALIDATED,
                        previous,
                        SessionState.ACTIVE,
                        "Session validated successfully");
                log.info("Session promoted to ACTIVE after first health check");
            } else {
                log.debug("Session health check passed (state={})", currentState.get());
            }

        } catch (Exception | KiteException e) {
            // KiteException extends Throwable (not Exception), so it must be caught separately
            int failures = consecutiveFailures.incrementAndGet();
            log.warn("Session health check failed ({}/{}): {}", failures, MAX_CONSECUTIVE_FAILURES, e.getMessage());

            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                handleSessionExpiry("Health check failed " + failures + " consecutive times: " + e.getMessage());
            }
        }
    }

    /**
     * Called by KiteAuthService after a new session is successfully established.
     * Transitions to CONNECTED and resets failure counters.
     */
    public void onSessionCreated(String message) {
        SessionState previous = currentState.getAndSet(SessionState.CONNECTED);
        consecutiveFailures.set(0);
        publishTransition(SessionEventType.SESSION_CREATED, previous, SessionState.CONNECTED, message);
        log.info("Session created: {}", message);
    }

    /**
     * Called by SessionExpiryService when the expiry warning threshold is reached.
     */
    public void onExpiryWarning() {
        SessionState previous = currentState.get();
        if (previous == SessionState.ACTIVE || previous == SessionState.CONNECTED) {
            currentState.set(SessionState.EXPIRY_WARNING);
            publishTransition(
                    SessionEventType.SESSION_EXPIRY_WARNING,
                    previous,
                    SessionState.EXPIRY_WARNING,
                    "Session approaching expiry");
        }
    }

    /**
     * Transitions session to EXPIRED. Called when:
     * - Health check fails 3 consecutive times
     * - Session TTL expires
     * - Token is invalidated by another login (403)
     */
    public void handleSessionExpiry(String reason) {
        SessionState previous = currentState.getAndSet(SessionState.EXPIRED);
        if (previous != SessionState.EXPIRED) {
            consecutiveFailures.set(0);
            publishTransition(SessionEventType.SESSION_EXPIRED, previous, SessionState.EXPIRED, reason);
            log.error("Session expired: {}", reason);
        }
    }

    /**
     * Called when the session is invalidated by a concurrent login from another device (403).
     */
    public void onSessionInvalidated(String reason) {
        SessionState previous = currentState.getAndSet(SessionState.EXPIRED);
        if (previous != SessionState.EXPIRED) {
            consecutiveFailures.set(0);
            publishTransition(SessionEventType.SESSION_INVALIDATED, previous, SessionState.EXPIRED, reason);
            log.error("Session invalidated: {}", reason);
        }
    }

    /**
     * Transitions to AUTHENTICATING during re-authentication.
     */
    public void onReAuthStarted() {
        SessionState previous = currentState.getAndSet(SessionState.AUTHENTICATING);
        log.info("Re-authentication started (previous state: {})", previous);
    }

    /**
     * Called after successful re-authentication. Transitions to CONNECTED.
     */
    public void onReAuthCompleted() {
        SessionState previous = currentState.getAndSet(SessionState.CONNECTED);
        consecutiveFailures.set(0);
        publishTransition(
                SessionEventType.SESSION_RECONNECTED,
                previous,
                SessionState.CONNECTED,
                "Session re-established after re-authentication");
        log.info("Re-authentication completed successfully");
    }

    public SessionState getState() {
        return currentState.get();
    }

    /**
     * Returns true if the session is in a state where broker operations are possible.
     * CONNECTED, ACTIVE, and EXPIRY_WARNING all allow trading.
     */
    public boolean isSessionActive() {
        SessionState state = currentState.get();
        return state == SessionState.CONNECTED || state == SessionState.ACTIVE || state == SessionState.EXPIRY_WARNING;
    }

    /**
     * Returns the number of consecutive health check failures.
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    private void publishTransition(
            SessionEventType eventType, SessionState previous, SessionState newState, String message) {
        applicationEventPublisher.publishEvent(new SessionEvent(this, eventType, previous, newState, message));
    }
}
