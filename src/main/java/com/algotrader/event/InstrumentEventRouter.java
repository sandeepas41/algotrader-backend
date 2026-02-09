package com.algotrader.event;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maintains instrument-to-strategy subscription mappings for efficient tick routing.
 *
 * <p>Instead of iterating over all strategies for every tick (O(N)), this router
 * provides O(1) lookup to find which strategies care about a given instrument.
 * When a TickEvent arrives, the StrategyEngine queries this router to get only
 * the strategies subscribed to that instrument token.
 *
 * <p>Thread safety: Uses ConcurrentHashMap with CopyOnWriteArrayList values.
 * Subscription changes (strategy deploy/close) are infrequent compared to tick
 * lookups, so CopyOnWriteArrayList's fast iteration at the cost of slow writes
 * is the right trade-off.
 *
 * <p>The router stores strategy IDs (not strategy objects) to avoid holding
 * references to mutable strategy state. The StrategyEngine resolves IDs to
 * live strategy instances at evaluation time.
 */
@Component
public class InstrumentEventRouter {

    private static final Logger log = LoggerFactory.getLogger(InstrumentEventRouter.class);

    /**
     * Maps instrumentToken -> list of strategy IDs subscribed to that instrument.
     * CopyOnWriteArrayList for safe concurrent iteration during tick processing.
     */
    private final Map<Long, CopyOnWriteArrayList<String>> subscriptions = new ConcurrentHashMap<>();

    /**
     * Registers a strategy as interested in ticks for a specific instrument.
     *
     * @param instrumentToken the Kite instrument token
     * @param strategyId      the strategy ID to receive tick notifications
     */
    public void subscribe(long instrumentToken, String strategyId) {
        subscriptions
                .computeIfAbsent(instrumentToken, k -> new CopyOnWriteArrayList<>())
                .addIfAbsent(strategyId);
        log.debug("Strategy {} subscribed to instrument {}", strategyId, instrumentToken);
    }

    /**
     * Registers a strategy for multiple instruments at once.
     * Typically called when a strategy is deployed and its legs define instrument tokens.
     */
    public void subscribeAll(Set<Long> instrumentTokens, String strategyId) {
        for (long token : instrumentTokens) {
            subscribe(token, strategyId);
        }
    }

    /**
     * Removes a strategy's subscription for a specific instrument.
     *
     * @param instrumentToken the Kite instrument token
     * @param strategyId      the strategy ID to unsubscribe
     */
    public void unsubscribe(long instrumentToken, String strategyId) {
        CopyOnWriteArrayList<String> strategies = subscriptions.get(instrumentToken);
        if (strategies != null) {
            strategies.remove(strategyId);
            // Clean up empty lists to prevent memory leak from closed instruments
            if (strategies.isEmpty()) {
                subscriptions.remove(instrumentToken);
            }
        }
        log.debug("Strategy {} unsubscribed from instrument {}", strategyId, instrumentToken);
    }

    /**
     * Removes a strategy from all instrument subscriptions.
     * Called when a strategy is closed or undeployed.
     */
    public void unsubscribeAll(String strategyId) {
        subscriptions.forEach((token, strategies) -> {
            strategies.remove(strategyId);
            if (strategies.isEmpty()) {
                subscriptions.remove(token);
            }
        });
        log.debug("Strategy {} unsubscribed from all instruments", strategyId);
    }

    /**
     * Returns the strategy IDs subscribed to a given instrument token.
     *
     * <p>This is the hot path — called once per tick per instrument. The returned
     * list is a CopyOnWriteArrayList snapshot, safe to iterate without synchronization.
     *
     * @param instrumentToken the Kite instrument token from the tick
     * @return list of strategy IDs (may be empty, never null)
     */
    public List<String> getSubscribedStrategies(long instrumentToken) {
        CopyOnWriteArrayList<String> strategies = subscriptions.get(instrumentToken);
        return strategies != null ? strategies : List.of();
    }

    /**
     * Returns all instruments that have at least one strategy subscribed.
     * Useful for the InstrumentSubscriptionManager to know which instruments
     * need market data subscriptions.
     */
    public Set<Long> getSubscribedInstruments() {
        return subscriptions.keySet();
    }

    /**
     * Returns the total number of instrument-strategy subscriptions.
     * Used for monitoring/metrics.
     */
    public int getSubscriptionCount() {
        return subscriptions.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Clears all subscriptions. Used during system reset or testing.
     */
    public void clearAll() {
        subscriptions.clear();
        log.info("All instrument event subscriptions cleared");
    }
}
