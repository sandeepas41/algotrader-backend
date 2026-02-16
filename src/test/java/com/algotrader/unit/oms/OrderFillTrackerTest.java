package com.algotrader.unit.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.model.Order;
import com.algotrader.event.OrderEvent;
import com.algotrader.event.OrderEventType;
import com.algotrader.oms.OrderFillTracker;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OrderFillTracker}, which tracks in-flight order fills
 * asynchronously using Spring's event system.
 */
class OrderFillTrackerTest {

    private OrderFillTracker orderFillTracker;

    @BeforeEach
    void setUp() {
        orderFillTracker = new OrderFillTracker();
    }

    @Test
    @DisplayName("Future completes when all expected fills arrive")
    void awaitFills_completesOnAllFills() throws Exception {
        CompletableFuture<Void> future = orderFillTracker.awaitFills("group-1", 2);

        // Simulate two fills
        publishFillEvent("group-1", "NIFTY25FEB24500CE");
        publishFillEvent("group-1", "NIFTY25FEB24500PE");

        // Future should complete within a short time
        future.get(1, TimeUnit.SECONDS);
        assertThat(future).isCompleted();
    }

    @Test
    @DisplayName("Future does not complete until all fills arrive")
    void awaitFills_doesNotCompleteEarly() {
        CompletableFuture<Void> future = orderFillTracker.awaitFills("group-2", 2);

        // Only one fill
        publishFillEvent("group-2", "NIFTY25FEB24500CE");

        assertThat(future).isNotDone();
        assertThat(orderFillTracker.getPendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Future completes exceptionally on order rejection")
    void awaitFills_failsOnRejection() {
        CompletableFuture<Void> future = orderFillTracker.awaitFills("group-3", 2);

        // One fill, then rejection
        publishFillEvent("group-3", "NIFTY25FEB24500CE");
        publishRejectEvent("group-3", "NIFTY25FEB24500PE", "Insufficient margin");

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(OrderFillTracker.OrderRejectedException.class)
                .hasMessageContaining("Insufficient margin");
    }

    @Test
    @DisplayName("Events for unknown correlationId are ignored")
    void onOrderEvent_ignoresUnknownCorrelationId() {
        CompletableFuture<Void> future = orderFillTracker.awaitFills("group-4", 1);

        // Event for different correlationId
        publishFillEvent("unknown-group", "NIFTY25FEB24500CE");

        assertThat(future).isNotDone();
        assertThat(orderFillTracker.getPendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Events with null order or null correlationId are ignored")
    void onOrderEvent_ignoresNullFields() {
        orderFillTracker.awaitFills("group-5", 1);

        // Null order
        orderFillTracker.onOrderEvent(new OrderEvent(this, null, OrderEventType.FILLED));

        // Order with null correlationId
        Order orderNoCorr = Order.builder()
                .tradingSymbol("NIFTY25FEB24500CE")
                .status(OrderStatus.COMPLETE)
                .build();
        orderFillTracker.onOrderEvent(new OrderEvent(this, orderNoCorr, OrderEventType.FILLED));

        assertThat(orderFillTracker.getPendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("cancelAwait removes the pending await and cancels future")
    void cancelAwait_removesPending() {
        CompletableFuture<Void> future = orderFillTracker.awaitFills("group-6", 2);

        orderFillTracker.cancelAwait("group-6");

        assertThat(future).isCancelled();
        assertThat(orderFillTracker.getPendingCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("cancelAwait is idempotent for non-existent correlationId")
    void cancelAwait_idempotent() {
        // Should not throw
        orderFillTracker.cancelAwait("non-existent");
        assertThat(orderFillTracker.getPendingCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Single fill for single expected count completes immediately")
    void awaitFills_singleFill() throws Exception {
        CompletableFuture<Void> future = orderFillTracker.awaitFills("group-7", 1);

        publishFillEvent("group-7", "NIFTY25FEB24500CE");

        future.get(1, TimeUnit.SECONDS);
        assertThat(future).isCompleted();
        assertThat(orderFillTracker.getPendingCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Multiple concurrent awaits track independently")
    void awaitFills_multipleConcurrent() throws Exception {
        CompletableFuture<Void> future1 = orderFillTracker.awaitFills("group-A", 1);
        CompletableFuture<Void> future2 = orderFillTracker.awaitFills("group-B", 1);

        assertThat(orderFillTracker.getPendingCount()).isEqualTo(2);

        publishFillEvent("group-A", "NIFTY25FEB24500CE");

        future1.get(1, TimeUnit.SECONDS);
        assertThat(future1).isCompleted();
        assertThat(future2).isNotDone();
        assertThat(orderFillTracker.getPendingCount()).isEqualTo(1);

        publishFillEvent("group-B", "NIFTY25FEB24500PE");

        future2.get(1, TimeUnit.SECONDS);
        assertThat(future2).isCompleted();
        assertThat(orderFillTracker.getPendingCount()).isEqualTo(0);
    }

    // ---- Helpers ----

    private void publishFillEvent(String correlationId, String symbol) {
        Order order = Order.builder()
                .correlationId(correlationId)
                .tradingSymbol(symbol)
                .status(OrderStatus.COMPLETE)
                .build();
        orderFillTracker.onOrderEvent(new OrderEvent(this, order, OrderEventType.FILLED));
    }

    private void publishRejectEvent(String correlationId, String symbol, String reason) {
        Order order = Order.builder()
                .correlationId(correlationId)
                .tradingSymbol(symbol)
                .status(OrderStatus.REJECTED)
                .rejectionReason(reason)
                .build();
        orderFillTracker.onOrderEvent(new OrderEvent(this, order, OrderEventType.REJECTED));
    }
}
