package com.algotrader.unit.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.oms.IdempotencyService;
import com.algotrader.oms.OrderQueue;
import com.algotrader.oms.OrderRequest;
import com.algotrader.oms.OrderRouteResult;
import com.algotrader.oms.OrderRouter;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for OrderRouter covering the full routing pipeline:
 * kill switch gating, idempotency checks, queue enqueue, and decision logging.
 */
class OrderRouterTest {

    private IdempotencyService idempotencyService;
    private OrderQueue orderQueue;
    private EventPublisherHelper eventPublisherHelper;
    private OrderRouter orderRouter;

    @BeforeEach
    void setUp() {
        idempotencyService = mock(IdempotencyService.class);
        orderQueue = mock(OrderQueue.class);
        eventPublisherHelper = mock(EventPublisherHelper.class);

        orderRouter = new OrderRouter(idempotencyService, orderQueue, eventPublisherHelper);

        // Default: all orders are unique
        when(idempotencyService.isUnique(any())).thenReturn(true);
    }

    private OrderRequest sampleRequest() {
        return OrderRequest.builder()
                .instrumentToken(256265L)
                .tradingSymbol("NIFTY24FEB22000CE")
                .exchange("NFO")
                .side(OrderSide.SELL)
                .type(OrderType.LIMIT)
                .product("NRML")
                .quantity(50)
                .price(BigDecimal.valueOf(150.00))
                .strategyId("STR1")
                .correlationId("COR-001")
                .build();
    }

    @Nested
    @DisplayName("Successful Order Routing")
    class SuccessfulRouting {

        @Test
        @DisplayName("Valid order passes through pipeline and returns accepted result")
        void validOrderAccepted() {
            OrderRouteResult result = orderRouter.route(sampleRequest(), OrderPriority.STRATEGY_ENTRY);

            assertThat(result.isAccepted()).isTrue();
            // Order ID is null because order is enqueued, not yet executed
            assertThat(result.getOrderId()).isNull();
            assertThat(result.getRejectionReason()).isNull();
        }

        @Test
        @DisplayName("Successful route enqueues order to OrderQueue")
        void successfulRouteEnqueuesOrder() {
            OrderRequest request = sampleRequest();

            orderRouter.route(request, OrderPriority.MANUAL);

            verify(orderQueue).enqueue(request, OrderPriority.MANUAL);
        }

        @Test
        @DisplayName("Successful route marks idempotency key")
        void successfulRouteMarksIdempotency() {
            OrderRequest request = sampleRequest();

            orderRouter.route(request, OrderPriority.STRATEGY_ENTRY);

            verify(idempotencyService).markProcessed(request);
        }

        @Test
        @DisplayName("Successful route logs acceptance decision")
        void successfulRouteLogsDecision() {
            orderRouter.route(sampleRequest(), OrderPriority.STRATEGY_ENTRY);

            verify(eventPublisherHelper).publishDecision(any(), eq("ORDER"), anyString(), eq("STR1"), any(Map.class));
        }

        @Test
        @DisplayName("Enqueued order preserves priority level")
        void enqueuedOrderPreservesPriority() {
            OrderRequest request = sampleRequest();

            orderRouter.route(request, OrderPriority.RISK_EXIT);

            verify(orderQueue).enqueue(request, OrderPriority.RISK_EXIT);
        }
    }

    @Nested
    @DisplayName("Kill Switch Blocking")
    class KillSwitchBlocking {

        @Test
        @DisplayName("Kill switch blocks non-KILL_SWITCH orders")
        void killSwitchBlocksNormalOrders() {
            orderRouter.activateKillSwitch();

            OrderRouteResult result = orderRouter.route(sampleRequest(), OrderPriority.MANUAL);

            assertThat(result.isAccepted()).isFalse();
            assertThat(result.getRejectionReason()).contains("Kill switch");
            verify(orderQueue, never()).enqueue(any(), any());
        }

        @Test
        @DisplayName("Kill switch blocks STRATEGY_ENTRY orders")
        void killSwitchBlocksStrategyEntry() {
            orderRouter.activateKillSwitch();

            OrderRouteResult result = orderRouter.route(sampleRequest(), OrderPriority.STRATEGY_ENTRY);

            assertThat(result.isAccepted()).isFalse();
        }

        @Test
        @DisplayName("Kill switch allows KILL_SWITCH priority orders through")
        void killSwitchAllowsKillSwitchOrders() {
            orderRouter.activateKillSwitch();

            OrderRouteResult result = orderRouter.route(sampleRequest(), OrderPriority.KILL_SWITCH);

            assertThat(result.isAccepted()).isTrue();
            verify(orderQueue).enqueue(any(), eq(OrderPriority.KILL_SWITCH));
        }

        @Test
        @DisplayName("Deactivated kill switch allows normal orders")
        void deactivatedKillSwitchAllowsOrders() {
            orderRouter.activateKillSwitch();
            orderRouter.deactivateKillSwitch();

            OrderRouteResult result = orderRouter.route(sampleRequest(), OrderPriority.MANUAL);

            assertThat(result.isAccepted()).isTrue();
        }

        @Test
        @DisplayName("isKillSwitchActive reflects current state")
        void isKillSwitchActiveReflectsState() {
            assertThat(orderRouter.isKillSwitchActive()).isFalse();

            orderRouter.activateKillSwitch();
            assertThat(orderRouter.isKillSwitchActive()).isTrue();

            orderRouter.deactivateKillSwitch();
            assertThat(orderRouter.isKillSwitchActive()).isFalse();
        }

        @Test
        @DisplayName("Kill switch rejection logs decision event")
        void killSwitchRejectionLogsDecision() {
            orderRouter.activateKillSwitch();

            orderRouter.route(sampleRequest(), OrderPriority.MANUAL);

            verify(eventPublisherHelper).publishDecision(any(), eq("ORDER"), anyString(), eq("STR1"), any(Map.class));
        }
    }

    @Nested
    @DisplayName("Idempotency Rejection")
    class IdempotencyRejection {

        @Test
        @DisplayName("Duplicate order is rejected")
        void duplicateOrderRejected() {
            when(idempotencyService.isUnique(any())).thenReturn(false);

            OrderRouteResult result = orderRouter.route(sampleRequest(), OrderPriority.STRATEGY_ENTRY);

            assertThat(result.isAccepted()).isFalse();
            assertThat(result.getRejectionReason()).contains("Duplicate");
            verify(orderQueue, never()).enqueue(any(), any());
        }

        @Test
        @DisplayName("Duplicate order does not mark idempotency key")
        void duplicateDoesNotMarkKey() {
            when(idempotencyService.isUnique(any())).thenReturn(false);

            orderRouter.route(sampleRequest(), OrderPriority.STRATEGY_ENTRY);

            verify(idempotencyService, never()).markProcessed(any());
        }

        @Test
        @DisplayName("Duplicate order logs rejection decision")
        void duplicateLogsDecision() {
            when(idempotencyService.isUnique(any())).thenReturn(false);

            orderRouter.route(sampleRequest(), OrderPriority.STRATEGY_ENTRY);

            verify(eventPublisherHelper).publishDecision(any(), eq("ORDER"), anyString(), eq("STR1"), any(Map.class));
        }
    }

    @Nested
    @DisplayName("All Priority Levels")
    class AllPriorityLevels {

        @Test
        @DisplayName("All 6 priority levels can route orders successfully")
        void allPriorityLevelsWork() {
            for (OrderPriority priority : OrderPriority.values()) {
                OrderRouteResult result = orderRouter.route(sampleRequest(), priority);
                assertThat(result.isAccepted())
                        .as("Priority %s should be accepted", priority)
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Decision Logging")
    class DecisionLogging {

        @Test
        @DisplayName("Decision context includes correlationId and priority")
        void decisionContextIncludesCorrelationAndPriority() {
            orderRouter.route(sampleRequest(), OrderPriority.STRATEGY_ADJUSTMENT);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
            verify(eventPublisherHelper)
                    .publishDecision(any(), eq("ORDER"), anyString(), eq("STR1"), contextCaptor.capture());

            Map<String, Object> context = contextCaptor.getValue();
            assertThat(context.get("correlationId")).isEqualTo("COR-001");
            assertThat(context.get("priority")).isEqualTo("STRATEGY_ADJUSTMENT");
            assertThat(context.get("accepted")).isEqualTo(true);
        }

        @Test
        @DisplayName("Kill switch activation/deactivation publishes system decision events")
        void killSwitchTogglePublishesDecisions() {
            orderRouter.activateKillSwitch();
            orderRouter.deactivateKillSwitch();

            verify(eventPublisherHelper)
                    .publishDecision(any(), eq("SYSTEM"), eq("Kill switch activated on OrderRouter"));
            verify(eventPublisherHelper)
                    .publishDecision(any(), eq("SYSTEM"), eq("Kill switch deactivated on OrderRouter"));
        }
    }
}
