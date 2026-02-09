package com.algotrader.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
import com.algotrader.oms.PrioritizedOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cross-service integration test for the order placement flow.
 * Wires real OrderRouter + OrderQueue + IdempotencyService to verify
 * the complete order routing pipeline: validate -> idempotency -> enqueue -> priority.
 */
@ExtendWith(MockitoExtension.class)
class OrderFlowIntegrationTest {

    @Mock
    private IdempotencyService idempotencyService;

    private OrderRouter orderRouter;
    private OrderQueue orderQueue;

    @BeforeEach
    void setUp() {
        EventPublisherHelper eventPublisherHelper = mock(EventPublisherHelper.class);
        orderQueue = new OrderQueue();
        orderRouter = new OrderRouter(idempotencyService, orderQueue, eventPublisherHelper);
    }

    @Test
    @DisplayName("Order flows from router to queue when all checks pass")
    void orderFlows_throughRouterToQueue() {
        OrderRequest request = buildOrder("NIFTY25FEB24500CE", 12345L, "corr-1");
        when(idempotencyService.isUnique(request)).thenReturn(true);

        OrderRouteResult result = orderRouter.route(request, OrderPriority.STRATEGY_ENTRY);

        assertThat(result.isAccepted()).isTrue();

        // Verify order appeared in queue
        PrioritizedOrder queued = orderQueue.poll();
        assertThat(queued).isNotNull();
        assertThat(queued.getOrderRequest().getTradingSymbol()).isEqualTo("NIFTY25FEB24500CE");
    }

    @Test
    @DisplayName("Duplicate orders are rejected by idempotency check")
    void duplicateOrder_rejectedByIdempotency() {
        OrderRequest request = buildOrder("NIFTY25FEB24500CE", 12345L, "corr-1");
        when(idempotencyService.isUnique(request)).thenReturn(false);

        OrderRouteResult result = orderRouter.route(request, OrderPriority.STRATEGY_ENTRY);

        assertThat(result.isAccepted()).isFalse();
        assertThat(result.getRejectionReason()).containsIgnoringCase("duplicate");
    }

    @Test
    @DisplayName("KILL_SWITCH priority orders bypass kill switch gate")
    void killSwitchOrders_bypassGate() {
        // Activate kill switch
        orderRouter.activateKillSwitch();

        OrderRequest request = buildOrder("NIFTY25FEB24500CE", 12345L, "ks-1");
        when(idempotencyService.isUnique(request)).thenReturn(true);

        // Kill switch priority should bypass
        OrderRouteResult result = orderRouter.route(request, OrderPriority.KILL_SWITCH);
        assertThat(result.isAccepted()).isTrue();

        // Regular priority should be blocked
        OrderRequest regularRequest = buildOrder("NIFTY25FEB24500PE", 67890L, "reg-1");
        OrderRouteResult regularResult = orderRouter.route(regularRequest, OrderPriority.STRATEGY_ENTRY);
        assertThat(regularResult.isAccepted()).isFalse();
    }

    @Test
    @DisplayName("Orders are dequeued in priority order")
    void orders_dequeuedByPriority() {
        OrderRequest lowPriority = buildOrder("LOW", 111L, "low-1");
        OrderRequest highPriority = buildOrder("HIGH", 222L, "high-1");
        OrderRequest killPriority = buildOrder("KILL", 333L, "kill-1");

        when(idempotencyService.isUnique(lowPriority)).thenReturn(true);
        when(idempotencyService.isUnique(highPriority)).thenReturn(true);
        when(idempotencyService.isUnique(killPriority)).thenReturn(true);

        // Enqueue in reverse priority order
        orderRouter.route(lowPriority, OrderPriority.MANUAL);
        orderRouter.route(highPriority, OrderPriority.STRATEGY_ENTRY);
        orderRouter.route(killPriority, OrderPriority.KILL_SWITCH);

        // Dequeue in priority order (KILL_SWITCH(0) > STRATEGY_ENTRY(4) > MANUAL(5))
        PrioritizedOrder first = orderQueue.poll();
        PrioritizedOrder second = orderQueue.poll();
        PrioritizedOrder third = orderQueue.poll();

        assertThat(first.getPriority()).isEqualTo(OrderPriority.KILL_SWITCH);
        assertThat(second.getPriority()).isEqualTo(OrderPriority.STRATEGY_ENTRY);
        assertThat(third.getPriority()).isEqualTo(OrderPriority.MANUAL);
    }

    @Test
    @DisplayName("Kill switch deactivation allows regular orders again")
    void killSwitchDeactivation_allowsOrders() {
        orderRouter.activateKillSwitch();

        OrderRequest blocked = buildOrder("BLOCKED", 111L, "blocked-1");
        assertThat(orderRouter.route(blocked, OrderPriority.STRATEGY_ENTRY).isAccepted())
                .isFalse();

        // Deactivate
        orderRouter.deactivateKillSwitch();

        OrderRequest allowed = buildOrder("ALLOWED", 222L, "allowed-1");
        when(idempotencyService.isUnique(allowed)).thenReturn(true);
        assertThat(orderRouter.route(allowed, OrderPriority.STRATEGY_ENTRY).isAccepted())
                .isTrue();
    }

    private OrderRequest buildOrder(String symbol, long token, String correlationId) {
        return OrderRequest.builder()
                .tradingSymbol(symbol)
                .instrumentToken(token)
                .side(OrderSide.SELL)
                .type(OrderType.MARKET)
                .quantity(50)
                .correlationId(correlationId)
                .build();
    }
}
