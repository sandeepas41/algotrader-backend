package com.algotrader.unit.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.event.InstrumentEventRouter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InstrumentEventRouter}.
 *
 * <p>Tests subscribe/unsubscribe operations, multi-strategy routing,
 * and edge cases (duplicate subscriptions, empty lookups).
 */
class InstrumentEventRouterTest {

    private InstrumentEventRouter instrumentEventRouter;

    @BeforeEach
    void setUp() {
        instrumentEventRouter = new InstrumentEventRouter();
    }

    @Nested
    @DisplayName("Subscribe")
    class SubscribeTests {

        @Test
        @DisplayName("subscribe registers a strategy for an instrument")
        void subscribesStrategy() {
            instrumentEventRouter.subscribe(256265L, "strat-1");

            List<String> strategies = instrumentEventRouter.getSubscribedStrategies(256265L);
            assertThat(strategies).containsExactly("strat-1");
        }

        @Test
        @DisplayName("subscribe multiple strategies to same instrument")
        void subscribesMultipleStrategies() {
            instrumentEventRouter.subscribe(256265L, "strat-1");
            instrumentEventRouter.subscribe(256265L, "strat-2");
            instrumentEventRouter.subscribe(256265L, "strat-3");

            List<String> strategies = instrumentEventRouter.getSubscribedStrategies(256265L);
            assertThat(strategies).containsExactly("strat-1", "strat-2", "strat-3");
        }

        @Test
        @DisplayName("duplicate subscribe is idempotent — no duplicate entries")
        void duplicateSubscribeIsIdempotent() {
            instrumentEventRouter.subscribe(256265L, "strat-1");
            instrumentEventRouter.subscribe(256265L, "strat-1");

            List<String> strategies = instrumentEventRouter.getSubscribedStrategies(256265L);
            assertThat(strategies).hasSize(1);
        }

        @Test
        @DisplayName("subscribeAll registers strategy for all instruments")
        void subscribeAll() {
            Set<Long> tokens = Set.of(256265L, 260105L, 261893L);
            instrumentEventRouter.subscribeAll(tokens, "strat-1");

            assertThat(instrumentEventRouter.getSubscribedStrategies(256265L)).containsExactly("strat-1");
            assertThat(instrumentEventRouter.getSubscribedStrategies(260105L)).containsExactly("strat-1");
            assertThat(instrumentEventRouter.getSubscribedStrategies(261893L)).containsExactly("strat-1");
        }
    }

    @Nested
    @DisplayName("Unsubscribe")
    class UnsubscribeTests {

        @Test
        @DisplayName("unsubscribe removes a strategy from an instrument")
        void unsubscribesStrategy() {
            instrumentEventRouter.subscribe(256265L, "strat-1");
            instrumentEventRouter.subscribe(256265L, "strat-2");

            instrumentEventRouter.unsubscribe(256265L, "strat-1");

            List<String> strategies = instrumentEventRouter.getSubscribedStrategies(256265L);
            assertThat(strategies).containsExactly("strat-2");
        }

        @Test
        @DisplayName("unsubscribe cleans up empty lists")
        void unsubscribeCleansUpEmptyList() {
            instrumentEventRouter.subscribe(256265L, "strat-1");
            instrumentEventRouter.unsubscribe(256265L, "strat-1");

            assertThat(instrumentEventRouter.getSubscribedInstruments()).isEmpty();
        }

        @Test
        @DisplayName("unsubscribe from non-existent instrument does not throw")
        void unsubscribeFromNonExistent() {
            instrumentEventRouter.unsubscribe(999999L, "strat-1");
            // Should not throw
        }

        @Test
        @DisplayName("unsubscribeAll removes strategy from all instruments")
        void unsubscribeAll() {
            instrumentEventRouter.subscribe(256265L, "strat-1");
            instrumentEventRouter.subscribe(260105L, "strat-1");
            instrumentEventRouter.subscribe(256265L, "strat-2");

            instrumentEventRouter.unsubscribeAll("strat-1");

            assertThat(instrumentEventRouter.getSubscribedStrategies(256265L)).containsExactly("strat-2");
            assertThat(instrumentEventRouter.getSubscribedStrategies(260105L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Routing")
    class RoutingTests {

        @Test
        @DisplayName("getSubscribedStrategies returns only strategies for that instrument")
        void routesOnlyToSubscribedStrategies() {
            // strat-1 watches NIFTY (256265) and BANKNIFTY (260105)
            instrumentEventRouter.subscribe(256265L, "strat-1");
            instrumentEventRouter.subscribe(260105L, "strat-1");

            // strat-2 watches only NIFTY (256265)
            instrumentEventRouter.subscribe(256265L, "strat-2");

            // strat-3 watches only BANKNIFTY (260105)
            instrumentEventRouter.subscribe(260105L, "strat-3");

            // NIFTY tick should reach strat-1 and strat-2 only
            assertThat(instrumentEventRouter.getSubscribedStrategies(256265L)).containsExactly("strat-1", "strat-2");

            // BANKNIFTY tick should reach strat-1 and strat-3 only
            assertThat(instrumentEventRouter.getSubscribedStrategies(260105L)).containsExactly("strat-1", "strat-3");
        }

        @Test
        @DisplayName("getSubscribedStrategies returns empty list for unsubscribed instrument")
        void returnsEmptyForUnsubscribedInstrument() {
            List<String> strategies = instrumentEventRouter.getSubscribedStrategies(999999L);
            assertThat(strategies).isEmpty();
        }
    }

    @Nested
    @DisplayName("Metrics")
    class MetricsTests {

        @Test
        @DisplayName("getSubscribedInstruments returns all instruments with subscriptions")
        void subscribedInstruments() {
            instrumentEventRouter.subscribe(256265L, "strat-1");
            instrumentEventRouter.subscribe(260105L, "strat-2");

            assertThat(instrumentEventRouter.getSubscribedInstruments()).containsExactlyInAnyOrder(256265L, 260105L);
        }

        @Test
        @DisplayName("getSubscriptionCount returns total instrument-strategy pairs")
        void subscriptionCount() {
            instrumentEventRouter.subscribe(256265L, "strat-1");
            instrumentEventRouter.subscribe(256265L, "strat-2");
            instrumentEventRouter.subscribe(260105L, "strat-1");

            // 2 strategies on instrument 256265 + 1 strategy on instrument 260105
            assertThat(instrumentEventRouter.getSubscriptionCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("clearAll removes all subscriptions")
        void clearAll() {
            instrumentEventRouter.subscribe(256265L, "strat-1");
            instrumentEventRouter.subscribe(260105L, "strat-2");

            instrumentEventRouter.clearAll();

            assertThat(instrumentEventRouter.getSubscribedInstruments()).isEmpty();
            assertThat(instrumentEventRouter.getSubscriptionCount()).isEqualTo(0);
        }
    }
}
