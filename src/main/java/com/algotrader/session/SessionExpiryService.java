package com.algotrader.session;

import com.algotrader.broker.KiteAuthService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Monitors the Kite session expiry countdown and fires warnings at 30-minute
 * and 5-minute marks before the token expires.
 *
 * <p>Kite tokens expire at 6 AM IST daily. This service checks every minute
 * whether the session is approaching expiry and triggers appropriate responses:
 * <ul>
 *   <li>30-min warning: informational, trader should plan to re-authenticate</li>
 *   <li>5-min warning: actionable, all strategies should be paused</li>
 *   <li>Expired: session marked as EXPIRED, live operations blocked</li>
 * </ul>
 *
 * <p>Also handles 403 auto-reauth: when called by the broker gateway after
 * a TokenException, triggers sidecar-based re-authentication.
 */
@Service
public class SessionExpiryService {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiryService.class);

    private final SessionHealthService sessionHealthService;
    private final KiteAuthService kiteAuthService;

    private final AtomicBoolean thirtyMinWarningFired = new AtomicBoolean(false);
    private final AtomicBoolean fiveMinWarningFired = new AtomicBoolean(false);

    public SessionExpiryService(SessionHealthService sessionHealthService, KiteAuthService kiteAuthService) {
        this.sessionHealthService = sessionHealthService;
        this.kiteAuthService = kiteAuthService;
    }

    /**
     * Checks session expiry every minute. Fires 30-min and 5-min warnings,
     * and transitions to EXPIRED when the token TTL is exhausted.
     */
    @Scheduled(fixedRate = 60_000)
    public void checkExpiry() {
        if (!sessionHealthService.isSessionActive()) {
            return;
        }

        LocalDateTime expiryTime = kiteAuthService.getTokenExpiry();
        if (expiryTime == null) {
            return;
        }

        checkExpiry(expiryTime, LocalDateTime.now());
    }

    /**
     * Testable version: checks expiry based on the given expiry time and current time.
     */
    public void checkExpiry(LocalDateTime expiryTime, LocalDateTime now) {
        Duration timeUntilExpiry = Duration.between(now, expiryTime);

        // Already expired
        if (timeUntilExpiry.isNegative() || timeUntilExpiry.isZero()) {
            sessionHealthService.handleSessionExpiry("Session TTL expired");
            resetWarningFlags();
            return;
        }

        long minutesRemaining = timeUntilExpiry.toMinutes();

        // 30-minute warning (informational)
        if (minutesRemaining <= 30 && !thirtyMinWarningFired.getAndSet(true)) {
            log.warn("Session expiring in {} minutes", minutesRemaining);
            sessionHealthService.onExpiryWarning();
        }

        // 5-minute warning (actionable -- strategies should be paused)
        if (minutesRemaining <= 5 && !fiveMinWarningFired.getAndSet(true)) {
            log.error("Session expiring in {} minutes -- strategies should be paused", minutesRemaining);
            // #TODO: Pause all active strategies via StrategyEngine.pauseAllStrategies()
            // when StrategyEngine is implemented in Task 6.2
        }
    }

    /**
     * Called when any Kite API call returns 403 (TokenException).
     * Triggers auto-reauth via the sidecar if enabled.
     *
     * <p>This method is typically invoked by KiteBrokerGateway when it catches
     * a TokenException from any broker operation.
     */
    public void onTokenException() {
        log.error("TokenException detected -- triggering auto-reauth");

        // #TODO: Pause all active strategies via StrategyEngine.pauseAllStrategies()
        // when StrategyEngine is implemented in Task 6.2

        sessionHealthService.onSessionInvalidated("Session invalidated (403 TokenException from Kite API)");

        try {
            sessionHealthService.onReAuthStarted();
            kiteAuthService.reAuthenticate();
            sessionHealthService.onReAuthCompleted();
            resetWarningFlags();
            log.info("Auto-reauth successful after 403");

            // #TODO: Resume strategies via StrategyEngine.resumeAllStrategies()
            // and reconnect market data WebSocket via KiteMarketDataService.reconnect()
            // when those services are available
        } catch (Exception e) {
            log.error("Auto-reauth failed: {}", e.getMessage(), e);
            sessionHealthService.handleSessionExpiry("Auto-reauth failed: " + e.getMessage());
        }
    }

    /**
     * Called after successful manual or automatic re-authentication.
     * Resets warning flags so they can fire again for the new session.
     */
    public void resetWarningFlags() {
        thirtyMinWarningFired.set(false);
        fiveMinWarningFired.set(false);
    }

    /** Returns true if the 30-minute expiry warning has been fired. */
    public boolean isThirtyMinWarningFired() {
        return thirtyMinWarningFired.get();
    }

    /** Returns true if the 5-minute expiry warning has been fired. */
    public boolean isFiveMinWarningFired() {
        return fiveMinWarningFired.get();
    }
}
