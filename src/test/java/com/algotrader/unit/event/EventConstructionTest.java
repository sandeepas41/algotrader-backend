package com.algotrader.unit.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.MarketPhase;
import com.algotrader.domain.enums.MismatchType;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.model.AdjustmentAction;
import com.algotrader.domain.model.AdjustmentRule;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.PositionMismatch;
import com.algotrader.domain.model.ReconciliationResult;
import com.algotrader.domain.model.Strategy;
import com.algotrader.domain.model.Tick;
import com.algotrader.event.AdjustmentEvent;
import com.algotrader.event.AdjustmentStatus;
import com.algotrader.event.DecisionEvent;
import com.algotrader.event.MarketStatusEvent;
import com.algotrader.event.OrderEvent;
import com.algotrader.event.OrderEventType;
import com.algotrader.event.PositionEvent;
import com.algotrader.event.PositionEventType;
import com.algotrader.event.ReconciliationEvent;
import com.algotrader.event.RiskEvent;
import com.algotrader.event.RiskEventType;
import com.algotrader.event.RiskLevel;
import com.algotrader.event.SessionEvent;
import com.algotrader.event.SessionEventType;
import com.algotrader.event.StrategyEvent;
import com.algotrader.event.StrategyEventType;
import com.algotrader.event.SystemEvent;
import com.algotrader.event.SystemEventType;
import com.algotrader.event.TickEvent;
import com.algotrader.session.SessionState;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests that all event types are constructed correctly with proper field values.
 * These are basic sanity tests ensuring event immutability and getter correctness.
 */
class EventConstructionTest {

    private static final Object SOURCE = "test-source";

    @Nested
    @DisplayName("TickEvent")
    class TickEventTests {

        @Test
        @DisplayName("stores tick and records receivedAt timestamp")
        void constructsCorrectly() {
            Tick tick = Tick.builder()
                    .instrumentToken(256265L)
                    .lastPrice(new BigDecimal("22500"))
                    .build();
            long before = System.nanoTime();

            TickEvent event = new TickEvent(SOURCE, tick);

            assertThat(event.getTick()).isSameAs(tick);
            assertThat(event.getReceivedAt()).isGreaterThanOrEqualTo(before);
            assertThat(event.getSource()).isEqualTo(SOURCE);
        }
    }

    @Nested
    @DisplayName("OrderEvent")
    class OrderEventTests {

        @Test
        @DisplayName("constructs with all fields")
        void constructsWithAllFields() {
            Order order = Order.builder()
                    .brokerOrderId("12345")
                    .status(OrderStatus.COMPLETE)
                    .build();

            OrderEvent event = new OrderEvent(SOURCE, order, OrderEventType.FILLED, OrderStatus.OPEN);

            assertThat(event.getOrder()).isSameAs(order);
            assertThat(event.getEventType()).isEqualTo(OrderEventType.FILLED);
            assertThat(event.getPreviousStatus()).isEqualTo(OrderStatus.OPEN);
        }

        @Test
        @DisplayName("constructs without previous status")
        void constructsWithoutPreviousStatus() {
            Order order = Order.builder().build();

            OrderEvent event = new OrderEvent(SOURCE, order, OrderEventType.PLACED);

            assertThat(event.getPreviousStatus()).isNull();
        }
    }

    @Nested
    @DisplayName("PositionEvent")
    class PositionEventTests {

        @Test
        @DisplayName("constructs with previous P&L")
        void constructsWithPreviousPnl() {
            Position position = Position.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(50)
                    .unrealizedPnl(new BigDecimal("5000"))
                    .build();

            PositionEvent event =
                    new PositionEvent(SOURCE, position, PositionEventType.UPDATED, new BigDecimal("4000"));

            assertThat(event.getPosition()).isSameAs(position);
            assertThat(event.getEventType()).isEqualTo(PositionEventType.UPDATED);
            assertThat(event.getPreviousPnl()).isEqualByComparingTo("4000");
        }

        @Test
        @DisplayName("constructs without previous P&L for OPENED events")
        void constructsWithoutPreviousPnl() {
            Position position = Position.builder().build();

            PositionEvent event = new PositionEvent(SOURCE, position, PositionEventType.OPENED);

            assertThat(event.getPreviousPnl()).isNull();
        }
    }

    @Nested
    @DisplayName("StrategyEvent")
    class StrategyEventTests {

        @Test
        @DisplayName("constructs with previous status")
        void constructsWithPreviousStatus() {
            Strategy strategy = Strategy.builder()
                    .id("strat-1")
                    .name("NIFTY Straddle")
                    .status(StrategyStatus.ACTIVE)
                    .build();

            StrategyEvent event = new StrategyEvent(SOURCE, strategy, StrategyEventType.ACTIVE, StrategyStatus.ARMED);

            assertThat(event.getStrategy()).isSameAs(strategy);
            assertThat(event.getEventType()).isEqualTo(StrategyEventType.ACTIVE);
            assertThat(event.getPreviousStatus()).isEqualTo(StrategyStatus.ARMED);
        }
    }

    @Nested
    @DisplayName("RiskEvent")
    class RiskEventTests {

        @Test
        @DisplayName("constructs with details map")
        void constructsWithDetails() {
            Map<String, Object> details = Map.of("currentLoss", -50000, "limit", -40000);

            RiskEvent event = new RiskEvent(
                    SOURCE, RiskEventType.DAILY_LOSS_LIMIT_BREACH, RiskLevel.CRITICAL, "Loss exceeded", details);

            assertThat(event.getEventType()).isEqualTo(RiskEventType.DAILY_LOSS_LIMIT_BREACH);
            assertThat(event.getLevel()).isEqualTo(RiskLevel.CRITICAL);
            assertThat(event.getMessage()).isEqualTo("Loss exceeded");
            assertThat(event.getDetails()).hasSize(2);
        }

        @Test
        @DisplayName("details map is a defensive copy — original modifications do not affect event")
        void detailsMapIsDefensiveCopy() {
            java.util.HashMap<String, Object> details = new java.util.HashMap<>();
            details.put("key", "value");

            RiskEvent event =
                    new RiskEvent(SOURCE, RiskEventType.KILL_SWITCH_TRIGGERED, RiskLevel.CRITICAL, "test", details);
            details.put("newKey", "newValue");

            assertThat(event.getDetails()).hasSize(1);
        }

        @Test
        @DisplayName("constructs with empty details when not provided")
        void constructsWithEmptyDetails() {
            RiskEvent event =
                    new RiskEvent(SOURCE, RiskEventType.MARGIN_UTILIZATION_HIGH, RiskLevel.WARNING, "Margin high");

            assertThat(event.getDetails()).isEmpty();
        }
    }

    @Nested
    @DisplayName("AdjustmentEvent")
    class AdjustmentEventTests {

        @Test
        @DisplayName("constructs with PENDING status by default")
        void defaultsPendingStatus() {
            AdjustmentRule rule = AdjustmentRule.builder().name("Delta drift").build();
            AdjustmentAction action = AdjustmentAction.builder().build();

            AdjustmentEvent event = new AdjustmentEvent(SOURCE, "strat-1", rule, action, null);

            assertThat(event.getStrategyId()).isEqualTo("strat-1");
            assertThat(event.getRule()).isSameAs(rule);
            assertThat(event.getStatus()).isEqualTo(AdjustmentStatus.PENDING);
            assertThat(event.getAffectedPosition()).isNull();
        }

        @Test
        @DisplayName("constructs with explicit status")
        void constructsWithExplicitStatus() {
            AdjustmentRule rule = AdjustmentRule.builder().build();
            AdjustmentAction action = AdjustmentAction.builder().build();

            AdjustmentEvent event =
                    new AdjustmentEvent(SOURCE, "strat-1", rule, action, null, AdjustmentStatus.COMPLETED);

            assertThat(event.getStatus()).isEqualTo(AdjustmentStatus.COMPLETED);
        }
    }

    @Nested
    @DisplayName("SessionEvent")
    class SessionEventTests {

        @Test
        @DisplayName("constructs with state transition and timestamp")
        void constructsWithStateTransition() {
            SessionEvent event = new SessionEvent(
                    SOURCE,
                    SessionEventType.SESSION_EXPIRED,
                    SessionState.ACTIVE,
                    SessionState.EXPIRED,
                    "Health check failed");

            assertThat(event.getEventType()).isEqualTo(SessionEventType.SESSION_EXPIRED);
            assertThat(event.getPreviousState()).isEqualTo(SessionState.ACTIVE);
            assertThat(event.getNewState()).isEqualTo(SessionState.EXPIRED);
            assertThat(event.getMessage()).isEqualTo("Health check failed");
            assertThat(event.getOccurredAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("MarketStatusEvent")
    class MarketStatusEventTests {

        @Test
        @DisplayName("constructs with phase transition and timestamp")
        void constructsWithPhaseTransition() {
            MarketStatusEvent event = new MarketStatusEvent(SOURCE, MarketPhase.PRE_OPEN, MarketPhase.NORMAL);

            assertThat(event.getPreviousPhase()).isEqualTo(MarketPhase.PRE_OPEN);
            assertThat(event.getCurrentPhase()).isEqualTo(MarketPhase.NORMAL);
            assertThat(event.getTransitionTime()).isNotNull();
        }
    }

    @Nested
    @DisplayName("ReconciliationEvent")
    class ReconciliationEventTests {

        @Test
        @DisplayName("constructs with result and manual flag")
        void constructsWithResult() {
            PositionMismatch mismatch = PositionMismatch.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .instrumentToken(256265L)
                    .type(MismatchType.QUANTITY_MISMATCH)
                    .localQuantity(50)
                    .brokerQuantity(75)
                    .build();

            ReconciliationResult result = ReconciliationResult.builder()
                    .trigger("MANUAL")
                    .mismatches(List.of(mismatch))
                    .localPositionCount(5)
                    .brokerPositionCount(6)
                    .timestamp(LocalDateTime.now())
                    .build();

            ReconciliationEvent event = new ReconciliationEvent(SOURCE, result, true);

            assertThat(event.getResult()).isSameAs(result);
            assertThat(event.isManual()).isTrue();
            assertThat(event.getReconciledAt()).isNotNull();
            assertThat(event.getResult().getMismatches()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("SystemEvent")
    class SystemEventTests {

        @Test
        @DisplayName("constructs with type, message, and details")
        void constructsWithDetails() {
            Map<String, Object> details = Map.of("startupDurationMs", 3500);

            SystemEvent event = new SystemEvent(SOURCE, SystemEventType.APPLICATION_READY, "Ready", details);

            assertThat(event.getEventType()).isEqualTo(SystemEventType.APPLICATION_READY);
            assertThat(event.getMessage()).isEqualTo("Ready");
            assertThat(event.getDetails()).containsEntry("startupDurationMs", 3500);
        }

        @Test
        @DisplayName("details map defaults to empty when using simple constructor")
        void detailsDefaultToEmpty() {
            SystemEvent event = new SystemEvent(SOURCE, SystemEventType.SHUTTING_DOWN, "Shutting down");

            assertThat(event.getDetails()).isEmpty();
        }
    }

    @Nested
    @DisplayName("DecisionEvent")
    class DecisionEventTests {

        @Test
        @DisplayName("constructs with all fields")
        void constructsWithAllFields() {
            Map<String, Object> context = Map.of("iv", 15.2);

            DecisionEvent event = new DecisionEvent(SOURCE, "ENTRY", "Straddle entry triggered", "strat-1", context);

            assertThat(event.getCategory()).isEqualTo("ENTRY");
            assertThat(event.getMessage()).isEqualTo("Straddle entry triggered");
            assertThat(event.getStrategyId()).isEqualTo("strat-1");
            assertThat(event.getContext()).containsEntry("iv", 15.2);
            assertThat(event.getOccurredAt()).isNotNull();
        }

        @Test
        @DisplayName("constructs with minimal arguments")
        void constructsMinimal() {
            DecisionEvent event = new DecisionEvent(SOURCE, "SYSTEM", "Kill switch activated");

            assertThat(event.getStrategyId()).isNull();
            assertThat(event.getContext()).isEmpty();
        }
    }
}
