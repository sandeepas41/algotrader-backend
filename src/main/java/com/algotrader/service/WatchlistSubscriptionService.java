package com.algotrader.service;

import com.algotrader.broker.InstrumentSubscriptionManager;
import com.algotrader.broker.KiteMarketDataService;
import com.algotrader.domain.enums.ExpiryType;
import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.enums.SubscriptionPriority;
import com.algotrader.domain.model.Instrument;
import com.algotrader.domain.model.WatchlistConfig;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Auto-subscribes instruments on startup based on enabled watchlist configs.
 *
 * <p>Called after instrument cache is populated by StartupAuthRunner. For each
 * enabled WatchlistConfig, resolves the nearest expiry, finds spot + FUT tokens,
 * and subscribes them via InstrumentSubscriptionManager (for priority tracking)
 * and KiteMarketDataService (for actual WebSocket subscription).
 *
 * <p>Subscriptions use MANUAL priority (lowest), so they're evictable by strategy
 * subscriptions if the 3000-instrument limit is reached.
 *
 * <p>Note: ATM-based option strike subscriptions are deferred to the frontend.
 * The FE's useOptionChainSubscription hook handles ATM ± N windowing based on
 * live spot price. This avoids the chicken-and-egg problem of needing spot LTP
 * (from WebSocket) to determine ATM before the WebSocket is streaming.
 */
@Service
public class WatchlistSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistSubscriptionService.class);

    private static final String SUBSCRIBER_PREFIX = "watchlist:";

    private final WatchlistConfigService watchlistConfigService;
    private final InstrumentService instrumentService;
    private final InstrumentSubscriptionManager instrumentSubscriptionManager;
    private final KiteMarketDataService kiteMarketDataService;

    public WatchlistSubscriptionService(
            WatchlistConfigService watchlistConfigService,
            InstrumentService instrumentService,
            InstrumentSubscriptionManager instrumentSubscriptionManager,
            KiteMarketDataService kiteMarketDataService) {
        this.watchlistConfigService = watchlistConfigService;
        this.instrumentService = instrumentService;
        this.instrumentSubscriptionManager = instrumentSubscriptionManager;
        this.kiteMarketDataService = kiteMarketDataService;
    }

    /**
     * Subscribes instruments for all enabled watchlist configs.
     * Called once after instruments are loaded on startup.
     *
     * <p>For each config, subscribes:
     * <ul>
     *   <li>Spot instrument token (NSE equity or index)</li>
     *   <li>Nearest FUT token for the resolved expiry</li>
     * </ul>
     *
     * <p>Option strike subscriptions (ATM ± N) are handled by the frontend
     * when the user opens the instrument explorer or option chain page.
     */
    public void subscribeAll() {
        List<WatchlistConfig> configs = watchlistConfigService.getEnabledConfigs();
        if (configs.isEmpty()) {
            log.info("No enabled watchlist configs found, skipping auto-subscription");
            return;
        }

        log.info("Processing {} enabled watchlist configs for auto-subscription", configs.size());

        int totalSubscribed = 0;
        for (WatchlistConfig config : configs) {
            int count = subscribeForConfig(config);
            totalSubscribed += count;
        }

        log.info(
                "Watchlist auto-subscription complete: {} tokens subscribed across {} configs",
                totalSubscribed,
                configs.size());
    }

    /**
     * Subscribes instruments for a single watchlist config.
     *
     * @return number of tokens newly subscribed
     */
    private int subscribeForConfig(WatchlistConfig config) {
        String underlying = config.getUnderlying();
        String subscriberKey = SUBSCRIBER_PREFIX + underlying;
        List<Long> tokens = new ArrayList<>();

        // 1. Spot instrument token
        Optional<Instrument> spotOpt = instrumentService.getSpotInstrument(underlying);
        if (spotOpt.isPresent()) {
            tokens.add(spotOpt.get().getToken());
        } else {
            log.warn("No spot instrument found for underlying: {}", underlying);
        }

        // 2. Nearest FUT token for the resolved expiry
        LocalDate expiry = resolveExpiry(underlying, config.getExpiryType());
        if (expiry != null) {
            List<Instrument> derivatives = instrumentService.getDerivativesForExpiry(underlying, expiry);
            derivatives.stream()
                    .filter(i -> InstrumentType.FUT == i.getType())
                    .findFirst()
                    .ifPresent(fut -> tokens.add(fut.getToken()));
        } else {
            log.warn("No expiry found for underlying: {}", underlying);
        }

        if (tokens.isEmpty()) {
            log.warn("No tokens to subscribe for watchlist config: underlying={}", underlying);
            return 0;
        }

        // Register with subscription manager for priority tracking
        List<Long> newTokens =
                instrumentSubscriptionManager.subscribe(subscriberKey, tokens, SubscriptionPriority.MANUAL);

        // Subscribe on WebSocket (queued if ticker not yet connected)
        if (!newTokens.isEmpty()) {
            kiteMarketDataService.subscribe(newTokens);
        }

        log.info(
                "Watchlist subscription for {}: {} tokens (spot={}, expiry={})",
                underlying,
                tokens.size(),
                spotOpt.isPresent(),
                expiry);

        return newTokens.size();
    }

    /**
     * Resolves the target expiry based on the config's ExpiryType.
     * NEAREST_WEEKLY returns the earliest expiry on or after today.
     * NEAREST_MONTHLY returns the earliest monthly expiry (last Thursday of month).
     */
    private LocalDate resolveExpiry(String underlying, ExpiryType expiryType) {
        List<LocalDate> expiries = instrumentService.getExpiries(underlying);
        if (expiries.isEmpty()) {
            return null;
        }

        LocalDate today = LocalDate.now();

        if (expiryType == ExpiryType.NEAREST_WEEKLY) {
            // First expiry on or after today
            return expiries.stream().filter(e -> !e.isBefore(today)).findFirst().orElse(null);
        }

        // NEAREST_MONTHLY: find the nearest expiry that's a month-end expiry
        // Monthly expiries are typically the last Thursday of the month.
        // Heuristic: pick the latest expiry in each month, then find the nearest.
        return expiries.stream()
                .filter(e -> !e.isBefore(today))
                .filter(e -> isMonthlyExpiry(e, expiries))
                .findFirst()
                .orElse(null);
    }

    /**
     * Checks if an expiry date is the last expiry of its month (monthly expiry).
     * A monthly expiry is one where no later expiry exists in the same month.
     */
    private boolean isMonthlyExpiry(LocalDate expiry, List<LocalDate> allExpiries) {
        return allExpiries.stream()
                .noneMatch(
                        e -> e.getYear() == expiry.getYear() && e.getMonth() == expiry.getMonth() && e.isAfter(expiry));
    }
}
