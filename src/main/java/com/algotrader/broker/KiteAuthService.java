package com.algotrader.broker;

import com.algotrader.config.KiteConfig;
import com.algotrader.entity.KiteSessionEntity;
import com.algotrader.exception.BrokerException;
import com.algotrader.repository.jpa.KiteSessionJpaRepository;
import com.algotrader.repository.redis.KiteSessionRedisRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Manages the Kite Connect OAuth lifecycle: login URL generation, callback handling,
 * automated Playwright login, and on-demand re-authentication.
 *
 * <p>Operates on a shared {@link KiteConnect} Spring bean (created in
 * {@link KiteConfig}). After successful authentication, this service sets the
 * access token, user ID, and public token on that bean so all other broker
 * services can use the same authenticated SDK client.
 *
 * <p>On startup, checks H2 for a valid (non-expired) token. If none is found and
 * auto-login is enabled, uses {@link KiteLoginAutomation} to automate the Kite OAuth
 * flow via headless Chromium + TOTP. Tokens are persisted to both H2 (durable) and
 * Redis (fast reads with TTL aligned to 6 AM IST).
 *
 * <p>Provides a single-flight reauth gate using a {@link ReentrantLock} so that when
 * multiple threads detect a 403 simultaneously, only one thread triggers re-authentication
 * while the others wait for the result.
 *
 * <p>Kite tokens expire at 6 AM IST the next day.
 */
@Service
public class KiteAuthService {

    private static final Logger log = LoggerFactory.getLogger(KiteAuthService.class);

    private final KiteConfig kiteConfig;
    private final KiteConnect kiteConnect;
    private final KiteSessionJpaRepository kiteSessionJpaRepository;
    private final KiteSessionRedisRepository kiteSessionRedisRepository;
    private final KiteLoginAutomation kiteLoginAutomation;

    /** Single-flight gate: prevents concurrent reauth storms when multiple threads detect 403. */
    private final ReentrantLock reauthLock = new ReentrantLock();

    private volatile String accessToken;
    private volatile String currentUserId;
    private volatile String currentUserName;
    private volatile LocalDateTime tokenExpiry;

    public KiteAuthService(
            KiteConfig kiteConfig,
            KiteConnect kiteConnect,
            KiteSessionJpaRepository kiteSessionJpaRepository,
            KiteSessionRedisRepository kiteSessionRedisRepository,
            KiteLoginAutomation kiteLoginAutomation) {
        this.kiteConfig = kiteConfig;
        this.kiteConnect = kiteConnect;
        this.kiteSessionJpaRepository = kiteSessionJpaRepository;
        this.kiteSessionRedisRepository = kiteSessionRedisRepository;
        this.kiteLoginAutomation = kiteLoginAutomation;
    }

    /**
     * Startup auth flow: check H2 for a valid (non-expired) token.
     * If found, reuse it. If not, attempt automated login via Playwright.
     *
     * @throws BrokerException if token acquisition fails and auto-login is enabled
     */
    public void acquireTokenOnStartup() {
        // Step 1: Check H2 for a non-expired token
        Optional<KiteSessionEntity> existing = findValidSessionFromH2();
        if (existing.isPresent()) {
            KiteSessionEntity session = existing.get();
            restoreSession(session);
            log.info(
                    "Reusing valid token from H2 for user {} (expires: {})",
                    session.getUserId(),
                    session.getExpiresAt());
            return;
        }

        // Step 2: No valid token in H2 -- try automated Playwright login
        if (!kiteConfig.getAutoLogin().isEnabled()) {
            log.warn("No valid token in H2 and auto-login is disabled. System starts in degraded mode.");
            return;
        }

        log.info("No valid token in H2, starting automated Playwright login...");
        autoLogin();
    }

    /**
     * Automated login via Playwright (headless Chromium + TOTP).
     * Obtains a request_token and exchanges it for an access_token.
     *
     * @throws BrokerException if the Playwright automation or token exchange fails
     */
    public void autoLogin() {
        try {
            String requestToken = kiteLoginAutomation.obtainRequestToken();
            exchangeAndSaveToken(requestToken);
            log.info("Auto-login successful for user: {}", currentUserName);
        } catch (BrokerException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokerException("Auto-login via Playwright failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generates the Kite OAuth login URL for manual browser-based login (fallback).
     *
     * @return the Kite login URL with the configured API key
     */
    public String getLoginUrl() {
        return kiteConnect.getLoginURL();
    }

    /**
     * Handles the OAuth callback after manual login.
     * Exchanges the request token for an access token and persists the session.
     *
     * @param requestToken the request_token from the Kite OAuth redirect
     * @throws BrokerException if the token exchange fails
     */
    public void handleCallback(String requestToken) {
        try {
            exchangeAndSaveToken(requestToken);
            log.info("Manual login successful for user: {}", currentUserName);
        } catch (BrokerException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokerException("Callback token exchange failed: " + e.getMessage(), e);
        }
    }

    /**
     * On-demand re-authentication with single-flight gate.
     * When multiple threads detect 403 simultaneously, only one thread actually
     * performs the reauth; others wait for it to complete.
     *
     * @throws BrokerException if re-authentication fails
     */
    public void reAuthenticate() {
        if (!reauthLock.tryLock()) {
            // Another thread is already re-authenticating -- wait for it
            log.info("Reauth already in progress, waiting...");
            reauthLock.lock();
            try {
                // By the time we get the lock, reauth should be complete
                log.info("Reauth completed by another thread, token available: {}", isAuthenticated());
            } finally {
                reauthLock.unlock();
            }
            return;
        }

        try {
            log.info("Starting re-authentication...");
            if (kiteConfig.getAutoLogin().isEnabled()) {
                autoLogin();
            } else {
                log.warn("Auto-login disabled -- cannot auto-reauth. Manual login required via /api/auth/login-url");
                throw new BrokerException("Cannot re-authenticate: auto-login is disabled");
            }
        } finally {
            reauthLock.unlock();
        }
    }

    /**
     * Logs out by invalidating the Kite access token on the Kite API side,
     * clearing local in-memory state, and removing persisted sessions from H2 and Redis.
     *
     * <p>If the Kite API call to invalidate the token fails (e.g., token already expired),
     * local state is still cleared so the user can re-authenticate.
     */
    public void logout() {
        // Attempt to invalidate the token on Kite's side
        if (accessToken != null) {
            try {
                kiteConnect.invalidateAccessToken();
                log.info("Kite access token invalidated via API");
            } catch (KiteException | JSONException | IOException e) {
                // Token may already be expired or invalid -- still clear local state
                log.warn("Failed to invalidate Kite access token (may already be expired): {}", e.getMessage());
            }
        }

        // Clear local in-memory state
        String previousUserId = this.currentUserId;
        this.accessToken = null;
        this.currentUserId = null;
        this.currentUserName = null;
        this.tokenExpiry = null;

        // Remove persisted sessions
        kiteSessionJpaRepository.deleteAll();
        if (previousUserId != null) {
            kiteSessionRedisRepository.deleteSession(previousUserId);
        }

        log.info("Logout complete, all session state cleared");
    }

    public boolean isAuthenticated() {
        return accessToken != null && !isTokenExpired();
    }

    public String getAccessToken() {
        return accessToken;
    }

    public KiteConnect getKiteConnect() {
        return kiteConnect;
    }

    public String getCurrentUserId() {
        return currentUserId;
    }

    public String getCurrentUserName() {
        return currentUserName;
    }

    public LocalDateTime getTokenExpiry() {
        return tokenExpiry;
    }

    // ---- Private helpers ----

    /**
     * Exchanges a request token for an access token via the Kite API,
     * then configures the shared KiteConnect bean and persists the session.
     *
     * <p>Per the Kite SDK sample: after generateSession(), set accessToken,
     * publicToken, and userId on the shared KiteConnect instance.
     */
    private void exchangeAndSaveToken(String requestToken) {
        try {
            User user = kiteConnect.generateSession(requestToken, kiteConfig.getApiSecret());

            // Configure the shared KiteConnect bean (per SDK sample pattern)
            kiteConnect.setAccessToken(user.accessToken);
            kiteConnect.setPublicToken(user.publicToken);
            kiteConnect.setUserId(user.userId);

            this.accessToken = user.accessToken;
            this.currentUserId = user.userId;
            this.currentUserName = user.userName;

            // Kite tokens expire at 6 AM IST next day
            this.tokenExpiry = computeTokenExpiry();

            saveSessionToH2(user);
            saveSessionToRedis(user);
        } catch (KiteException | JSONException | IOException e) {
            throw new BrokerException("Failed to exchange request token: " + e.getMessage(), e);
        }
    }

    /**
     * Restores in-memory state and configures the shared KiteConnect bean
     * from a persisted H2 session entity.
     */
    private void restoreSession(KiteSessionEntity session) {
        this.accessToken = session.getAccessToken();
        this.currentUserId = session.getUserId();
        this.currentUserName = session.getUserName();
        this.tokenExpiry = session.getExpiresAt();

        // Configure the shared KiteConnect bean with the restored token
        kiteConnect.setAccessToken(session.getAccessToken());
        kiteConnect.setUserId(session.getUserId());

        // Also cache in Redis if not already present
        if (!kiteSessionRedisRepository.hasSession(session.getUserId())) {
            kiteSessionRedisRepository.storeSession(
                    session.getUserId(), session.getAccessToken(), session.getUserName());
        }
    }

    /** Finds a valid (non-expired) session from H2. */
    private Optional<KiteSessionEntity> findValidSessionFromH2() {
        return kiteSessionJpaRepository.findAll().stream()
                .filter(s -> s.getExpiresAt() != null && s.getExpiresAt().isAfter(LocalDateTime.now()))
                .findFirst();
    }

    private void saveSessionToH2(User user) {
        // Delete any previous sessions -- only one active session at a time
        kiteSessionJpaRepository.deleteAll();

        KiteSessionEntity entity = KiteSessionEntity.builder()
                .id(UUID.randomUUID().toString())
                .userId(user.userId)
                .accessToken(user.accessToken)
                .userName(user.userName)
                .loginTime(LocalDateTime.now())
                .expiresAt(computeTokenExpiry())
                .createdAt(LocalDateTime.now())
                .build();

        kiteSessionJpaRepository.save(entity);
        log.info("Session saved to H2, expires at {}", entity.getExpiresAt());
    }

    private void saveSessionToRedis(User user) {
        kiteSessionRedisRepository.storeSession(user.userId, user.accessToken, user.userName);
        log.info("Session cached in Redis for user {}", user.userId);
    }

    /**
     * Kite tokens expire at 6:00 AM IST the next day.
     * If current time is before 6 AM, token expires at 6 AM today;
     * otherwise at 6 AM tomorrow.
     */
    private LocalDateTime computeTokenExpiry() {
        LocalTime sixAm = LocalTime.of(6, 0);
        LocalDateTime now = LocalDateTime.now();
        if (now.toLocalTime().isBefore(sixAm)) {
            return LocalDate.now().atTime(sixAm);
        }
        return LocalDate.now().plusDays(1).atTime(sixAm);
    }

    private boolean isTokenExpired() {
        return tokenExpiry != null && LocalDateTime.now().isAfter(tokenExpiry);
    }
}
