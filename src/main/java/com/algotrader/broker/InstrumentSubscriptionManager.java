package com.algotrader.broker;

import com.algotrader.domain.enums.SubscriptionPriority;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Central manager for WebSocket instrument subscriptions.
 *
 * <p>Tracks which instruments are subscribed, who requested them (subscriber key),
 * and at what priority. Enforces the Kite WebSocket limit of 3000 instruments per
 * connection. When the limit is reached, lower-priority subscriptions are evicted
 * (MANUAL first, then CONDITION) to make room for higher-priority ones (STRATEGY).
 *
 * <p>The subscriber key uniquely identifies the requester (e.g., "strategy:iron-condor-1",
 * "condition:alert-42", "manual:watchlist"). Multiple subscribers can request the same
 * instrument token — the token is only unsubscribed from the WebSocket when all
 * subscribers release it.
 *
 * <p>This manager does NOT directly call the KiteTicker. Instead, it returns
 * lists of tokens to subscribe/unsubscribe, which the caller (KiteMarketDataService)
 * applies to the ticker.
 */
@Component
public class InstrumentSubscriptionManager {

    private static final Logger log = LoggerFactory.getLogger(InstrumentSubscriptionManager.class);

    /** Kite WebSocket limit: max instruments per connection. */
    static final int MAX_INSTRUMENTS = 3000;

    /**
     * Maps each subscription entry (subscriberKey + token) to its priority.
     * Key: "subscriberKey:instrumentToken", Value: priority.
     */
    private final Map<String, SubscriptionEntry> subscriptions = new ConcurrentHashMap<>();

    /**
     * Tracks all currently active instrument tokens (union of all subscribers).
     * Used for quick membership checks and count enforcement.
     */
    private final Set<Long> activeTokens = ConcurrentHashMap.newKeySet();

    /**
     * Adds instrument tokens for a subscriber. Returns tokens that need to be
     * newly subscribed on the WebSocket (tokens not already active).
     *
     * <p>If adding these tokens would exceed the limit, attempts to evict
     * lower-priority subscriptions. Returns empty list if eviction cannot free
     * enough capacity.
     *
     * @param subscriberKey unique key for the subscriber (e.g., "strategy:my-strat-1")
     * @param tokens instrument tokens to subscribe
     * @param priority subscription priority level
     * @return tokens that need to be newly subscribed on the WebSocket
     */
    public List<Long> subscribe(String subscriberKey, List<Long> tokens, SubscriptionPriority priority) {
        List<Long> newTokens =
                tokens.stream().filter(t -> !activeTokens.contains(t)).toList();

        int needed = newTokens.size();
        int available = MAX_INSTRUMENTS - activeTokens.size();

        if (needed > available) {
            int toEvict = needed - available;
            List<Long> evicted = evictLowerPriority(priority, toEvict);
            if (evicted.size() < toEvict) {
                log.warn(
                        "Cannot subscribe {} tokens for {}: need {} slots, only freed {}. Limit: {}",
                        tokens.size(),
                        subscriberKey,
                        toEvict,
                        evicted.size(),
                        MAX_INSTRUMENTS);
                return Collections.emptyList();
            }
            log.info(
                    "Evicted {} lower-priority subscriptions to make room for {} (priority: {})",
                    evicted.size(),
                    subscriberKey,
                    priority);
        }

        // Register all tokens for this subscriber
        for (Long token : tokens) {
            String entryKey = entryKey(subscriberKey, token);
            subscriptions.put(entryKey, new SubscriptionEntry(subscriberKey, token, priority));
            activeTokens.add(token);
        }

        log.info(
                "Subscribed {} tokens for {} (priority: {}, total active: {})",
                tokens.size(),
                subscriberKey,
                priority,
                activeTokens.size());

        return newTokens;
    }

    /**
     * Removes instrument tokens for a subscriber. Returns tokens that should be
     * unsubscribed from the WebSocket (tokens no longer needed by any subscriber).
     *
     * @param subscriberKey unique key for the subscriber
     * @param tokens instrument tokens to release
     * @return tokens that should be unsubscribed from the WebSocket
     */
    public List<Long> unsubscribe(String subscriberKey, List<Long> tokens) {
        List<Long> toUnsubscribe = new ArrayList<>();

        for (Long token : tokens) {
            subscriptions.remove(entryKey(subscriberKey, token));

            // Only unsubscribe from WebSocket if no other subscriber needs this token
            boolean stillNeeded = subscriptions.values().stream().anyMatch(e -> e.token.equals(token));
            if (!stillNeeded) {
                activeTokens.remove(token);
                toUnsubscribe.add(token);
            }
        }

        log.info(
                "Unsubscribed {} tokens for {} ({} removed from WebSocket, total active: {})",
                tokens.size(),
                subscriberKey,
                toUnsubscribe.size(),
                activeTokens.size());

        return toUnsubscribe;
    }

    /**
     * Removes all subscriptions for a subscriber. Returns tokens that should be
     * unsubscribed from the WebSocket.
     *
     * @param subscriberKey unique key for the subscriber
     * @return tokens that should be unsubscribed from the WebSocket
     */
    public List<Long> unsubscribeAll(String subscriberKey) {
        List<Long> subscriberTokens = subscriptions.values().stream()
                .filter(e -> subscriberKey.equals(e.subscriberKey))
                .map(e -> e.token)
                .toList();

        return unsubscribe(subscriberKey, subscriberTokens);
    }

    /** Returns all currently active instrument tokens. */
    public Set<Long> getActiveTokens() {
        return Collections.unmodifiableSet(activeTokens);
    }

    /** Returns the number of currently subscribed instruments. */
    public int getActiveCount() {
        return activeTokens.size();
    }

    /** Returns true if the given token is currently subscribed. */
    public boolean isSubscribed(Long token) {
        return activeTokens.contains(token);
    }

    /**
     * Evicts lower-priority subscriptions to free capacity.
     * Evicts MANUAL first, then CONDITION. Never evicts STRATEGY.
     *
     * @param incomingPriority the priority of the new subscription request
     * @param count number of slots to free
     * @return tokens that were evicted and should be unsubscribed
     */
    private List<Long> evictLowerPriority(SubscriptionPriority incomingPriority, int count) {
        // Find entries with lower priority, sorted lowest-priority first
        List<SubscriptionEntry> candidates = subscriptions.values().stream()
                .filter(e -> e.priority.ordinal() < incomingPriority.ordinal())
                .sorted(Comparator.comparingInt(e -> e.priority.ordinal()))
                .toList();

        List<Long> evicted = new ArrayList<>();
        Set<String> evictedKeys = ConcurrentHashMap.newKeySet();

        for (SubscriptionEntry candidate : candidates) {
            if (evicted.size() >= count) {
                break;
            }

            String entryKey = entryKey(candidate.subscriberKey, candidate.token);
            if (evictedKeys.contains(entryKey)) {
                continue;
            }

            subscriptions.remove(entryKey);
            evictedKeys.add(entryKey);

            // Only count as evicted if no other subscriber needs this token
            boolean stillNeeded = subscriptions.values().stream().anyMatch(e -> e.token.equals(candidate.token));
            if (!stillNeeded) {
                activeTokens.remove(candidate.token);
                evicted.add(candidate.token);
            }
        }

        return evicted;
    }

    private String entryKey(String subscriberKey, Long token) {
        return subscriberKey + ":" + token;
    }

    /** Internal record tracking a single subscription entry. */
    record SubscriptionEntry(String subscriberKey, Long token, SubscriptionPriority priority) {}
}
