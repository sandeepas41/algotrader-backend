package com.algotrader.unit.broker;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.broker.InstrumentSubscriptionManager;
import com.algotrader.domain.enums.SubscriptionPriority;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InstrumentSubscriptionManager}.
 *
 * <p>Tests subscription tracking, 3000-instrument limit enforcement,
 * priority-based eviction, and multi-subscriber reference counting.
 */
class InstrumentSubscriptionManagerTest {

    private InstrumentSubscriptionManager instrumentSubscriptionManager;

    @BeforeEach
    void setUp() {
        instrumentSubscriptionManager = new InstrumentSubscriptionManager();
    }

    @Test
    @DisplayName("subscribe: returns new tokens that need WebSocket subscription")
    void subscribeReturnsNewTokens() {
        List<Long> newTokens = instrumentSubscriptionManager.subscribe(
                "strategy:s1", List.of(100L, 200L, 300L), SubscriptionPriority.STRATEGY);

        assertThat(newTokens).containsExactly(100L, 200L, 300L);
        assertThat(instrumentSubscriptionManager.getActiveCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("subscribe: does not return already-subscribed tokens")
    void subscribeSkipsAlreadyActive() {
        instrumentSubscriptionManager.subscribe("strategy:s1", List.of(100L, 200L), SubscriptionPriority.STRATEGY);

        // Second subscriber requests overlapping tokens
        List<Long> newTokens = instrumentSubscriptionManager.subscribe(
                "strategy:s2", List.of(200L, 300L), SubscriptionPriority.STRATEGY);

        // Only 300 is new
        assertThat(newTokens).containsExactly(300L);
        assertThat(instrumentSubscriptionManager.getActiveCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("unsubscribe: returns tokens to remove only when no other subscriber needs them")
    void unsubscribeWithMultipleSubscribers() {
        instrumentSubscriptionManager.subscribe("strategy:s1", List.of(100L, 200L), SubscriptionPriority.STRATEGY);
        instrumentSubscriptionManager.subscribe("strategy:s2", List.of(200L, 300L), SubscriptionPriority.STRATEGY);

        // Unsubscribe s1 — token 200 still needed by s2, only 100 should be removed
        List<Long> toRemove = instrumentSubscriptionManager.unsubscribe("strategy:s1", List.of(100L, 200L));

        assertThat(toRemove).containsExactly(100L);
        assertThat(instrumentSubscriptionManager.getActiveCount()).isEqualTo(2);
        assertThat(instrumentSubscriptionManager.isSubscribed(200L)).isTrue();
    }

    @Test
    @DisplayName("unsubscribeAll: removes all tokens for a subscriber")
    void unsubscribeAll() {
        instrumentSubscriptionManager.subscribe(
                "strategy:s1", List.of(100L, 200L, 300L), SubscriptionPriority.STRATEGY);

        List<Long> removed = instrumentSubscriptionManager.unsubscribeAll("strategy:s1");

        assertThat(removed).containsExactlyInAnyOrder(100L, 200L, 300L);
        assertThat(instrumentSubscriptionManager.getActiveCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("enforces 3000 instrument limit")
    void enforcesMaxInstrumentsLimit() {
        // Fill up to 3000
        List<Long> tokens3000 = LongStream.rangeClosed(1, 3000).boxed().toList();
        instrumentSubscriptionManager.subscribe("strategy:s1", tokens3000, SubscriptionPriority.STRATEGY);

        assertThat(instrumentSubscriptionManager.getActiveCount()).isEqualTo(3000);

        // Try to add more at same priority — should fail (no lower priority to evict)
        List<Long> overflow =
                instrumentSubscriptionManager.subscribe("strategy:s2", List.of(9001L), SubscriptionPriority.STRATEGY);

        assertThat(overflow).isEmpty();
        assertThat(instrumentSubscriptionManager.getActiveCount()).isEqualTo(3000);
    }

    @Test
    @DisplayName("priority eviction: STRATEGY evicts MANUAL subscriptions")
    void strategyEvictsManual() {
        // Fill with MANUAL subscriptions
        List<Long> manualTokens = LongStream.rangeClosed(1, 3000).boxed().toList();
        instrumentSubscriptionManager.subscribe("manual:watchlist", manualTokens, SubscriptionPriority.MANUAL);

        assertThat(instrumentSubscriptionManager.getActiveCount()).isEqualTo(3000);

        // STRATEGY subscription should evict MANUAL
        List<Long> strategyTokens = List.of(5001L, 5002L, 5003L);
        List<Long> newTokens =
                instrumentSubscriptionManager.subscribe("strategy:s1", strategyTokens, SubscriptionPriority.STRATEGY);

        assertThat(newTokens).containsExactly(5001L, 5002L, 5003L);
        assertThat(instrumentSubscriptionManager.getActiveCount()).isEqualTo(3000);
        assertThat(instrumentSubscriptionManager.isSubscribed(5001L)).isTrue();
    }

    @Test
    @DisplayName("priority eviction: CONDITION evicts MANUAL but not STRATEGY")
    void conditionEvictsManualNotStrategy() {
        // Fill 2999 with STRATEGY, 1 with MANUAL
        List<Long> strategyTokens = LongStream.rangeClosed(1, 2999).boxed().toList();
        instrumentSubscriptionManager.subscribe("strategy:s1", strategyTokens, SubscriptionPriority.STRATEGY);
        instrumentSubscriptionManager.subscribe("manual:watchlist", List.of(4000L), SubscriptionPriority.MANUAL);

        assertThat(instrumentSubscriptionManager.getActiveCount()).isEqualTo(3000);

        // CONDITION can evict the 1 MANUAL subscription
        List<Long> condTokens = List.of(5001L);
        List<Long> newTokens = instrumentSubscriptionManager.subscribe(
                "condition:alert-1", condTokens, SubscriptionPriority.CONDITION);

        assertThat(newTokens).containsExactly(5001L);
        assertThat(instrumentSubscriptionManager.isSubscribed(4000L)).isFalse();

        // Try to add one more CONDITION — should fail (only STRATEGY left, can't evict)
        List<Long> overflow = instrumentSubscriptionManager.subscribe(
                "condition:alert-2", List.of(5002L), SubscriptionPriority.CONDITION);
        assertThat(overflow).isEmpty();
    }

    @Test
    @DisplayName("priority eviction: evicts MANUAL before CONDITION")
    void evictsManualBeforeCondition() {
        // Fill: 2998 STRATEGY, 1 CONDITION, 1 MANUAL
        List<Long> stratTokens = LongStream.rangeClosed(1, 2998).boxed().toList();
        instrumentSubscriptionManager.subscribe("strategy:s1", stratTokens, SubscriptionPriority.STRATEGY);
        instrumentSubscriptionManager.subscribe("condition:c1", List.of(3001L), SubscriptionPriority.CONDITION);
        instrumentSubscriptionManager.subscribe("manual:m1", List.of(3002L), SubscriptionPriority.MANUAL);

        assertThat(instrumentSubscriptionManager.getActiveCount()).isEqualTo(3000);

        // STRATEGY requesting 2 slots — should evict MANUAL first (3002), then CONDITION (3001)
        List<Long> newStratTokens = List.of(5001L, 5002L);
        List<Long> newTokens =
                instrumentSubscriptionManager.subscribe("strategy:s2", newStratTokens, SubscriptionPriority.STRATEGY);

        assertThat(newTokens).containsExactly(5001L, 5002L);
        assertThat(instrumentSubscriptionManager.isSubscribed(3002L)).isFalse(); // MANUAL evicted
        assertThat(instrumentSubscriptionManager.isSubscribed(3001L)).isFalse(); // CONDITION evicted
    }

    @Test
    @DisplayName("isSubscribed: returns correct state")
    void isSubscribed() {
        instrumentSubscriptionManager.subscribe("strategy:s1", List.of(100L), SubscriptionPriority.STRATEGY);

        assertThat(instrumentSubscriptionManager.isSubscribed(100L)).isTrue();
        assertThat(instrumentSubscriptionManager.isSubscribed(999L)).isFalse();
    }

    @Test
    @DisplayName("getActiveTokens: returns unmodifiable set")
    void getActiveTokensUnmodifiable() {
        instrumentSubscriptionManager.subscribe("strategy:s1", List.of(100L, 200L), SubscriptionPriority.STRATEGY);

        var activeTokens = instrumentSubscriptionManager.getActiveTokens();
        assertThat(activeTokens).containsExactlyInAnyOrder(100L, 200L);
    }
}
