package com.algotrader.broker;

import com.algotrader.service.InstrumentService;
import com.algotrader.service.WatchlistSubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs the Kite startup sequence after the application has fully started:
 * first acquires the Kite token, then loads today's instruments into memory.
 *
 * <p>Listens for {@link ApplicationReadyEvent} and delegates to
 * {@link KiteAuthService#acquireTokenOnStartup()}. If initial acquisition fails,
 * retries with exponential backoff (60s initial, doubling up to 5-minute max interval,
 * 10 retries) on a dedicated async thread so the main startup is never blocked.
 *
 * <p>After successful token acquisition, calls
 * {@link InstrumentService#loadInstrumentsOnStartup()} to populate the in-memory
 * instrument cache. Instrument loading requires a valid Kite session, so it always
 * runs after auth succeeds.
 *
 * <p>On persistent failure the system remains in degraded mode — all broker operations
 * will fail until the trader manually authenticates via /api/auth/login-url.
 */
@Component
public class StartupAuthRunner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupAuthRunner.class);

    private static final long INITIAL_RETRY_INTERVAL_MS = 60_000;
    private static final long MAX_RETRY_INTERVAL_MS = 300_000;
    private static final int MAX_RETRIES = 10;

    private final KiteAuthService kiteAuthService;
    private final KiteMarketDataService kiteMarketDataService;
    private final InstrumentService instrumentService;
    private final WatchlistSubscriptionService watchlistSubscriptionService;

    public StartupAuthRunner(
            KiteAuthService kiteAuthService,
            KiteMarketDataService kiteMarketDataService,
            InstrumentService instrumentService,
            WatchlistSubscriptionService watchlistSubscriptionService) {
        this.kiteAuthService = kiteAuthService;
        this.kiteMarketDataService = kiteMarketDataService;
        this.instrumentService = instrumentService;
        this.watchlistSubscriptionService = watchlistSubscriptionService;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        acquireTokenAsync();
    }

    /**
     * Attempts token acquisition on a separate thread so the main application context
     * startup is never blocked. Retries with exponential backoff on failure.
     */
    @Async("eventExecutor")
    public void acquireTokenAsync() {
        log.info("Startup: acquiring Kite token...");

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                kiteAuthService.acquireTokenOnStartup();
                if (kiteAuthService.isAuthenticated()) {
                    log.info("Kite token acquired successfully on attempt {}", attempt);
                    connectTicker();
                    loadInstruments();
                } else {
                    log.warn(
                            "Token acquisition completed but not authenticated (sidecar may be disabled). Degraded mode.");
                }
                return;
            } catch (Exception e) {
                long interval = Math.min(INITIAL_RETRY_INTERVAL_MS * (1L << (attempt - 1)), MAX_RETRY_INTERVAL_MS);
                log.warn(
                        "Token acquisition attempt {}/{} failed: {}. Retrying in {}s...",
                        attempt,
                        MAX_RETRIES,
                        e.getMessage(),
                        interval / 1000);

                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(interval);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Startup auth retry interrupted");
                        return;
                    }
                }
            }
        }

        log.error(
                "Failed to acquire Kite token after {} attempts. System in degraded mode. "
                        + "Manual login required via /api/auth/login-url",
                MAX_RETRIES);
    }

    /**
     * Connects the Kite WebSocket ticker for real-time market data.
     * Non-fatal: if connection fails, the ticker will auto-reconnect when subscriptions arrive.
     */
    private void connectTicker() {
        try {
            kiteMarketDataService.connect(kiteAuthService.getAccessToken());
            log.info("Kite ticker connection initiated");
        } catch (Exception e) {
            log.error("Failed to connect Kite ticker on startup: {}", e.getMessage());
        }
    }

    /**
     * Loads today's instruments into the in-memory cache, then subscribes
     * watchlist instruments for auto-configured underlyings.
     * Non-fatal: if instrument loading fails, auth is still valid and the system
     * can operate without instruments (e.g., manual order placement).
     */
    private void loadInstruments() {
        try {
            instrumentService.loadInstrumentsOnStartup();
            subscribeWatchlistInstruments();
        } catch (Exception e) {
            log.error(
                    "Failed to load instruments on startup: {}. System continues without instrument cache.",
                    e.getMessage());
        }
    }

    /**
     * Subscribes instruments for all enabled watchlist configs.
     * Non-fatal: failure here doesn't affect other startup operations.
     */
    private void subscribeWatchlistInstruments() {
        try {
            watchlistSubscriptionService.subscribeAll();
        } catch (Exception e) {
            log.error("Failed to auto-subscribe watchlist instruments: {}", e.getMessage());
        }
    }
}
