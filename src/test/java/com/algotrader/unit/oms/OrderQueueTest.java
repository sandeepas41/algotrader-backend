package com.algotrader.unit.oms;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.oms.OrderQueue;
import com.algotrader.oms.OrderRequest;
import com.algotrader.oms.PrioritizedOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OrderQueue covering priority ordering, FIFO within
 * same priority, blocking take(), and queue metrics.
 */
class OrderQueueTest {

    private OrderQueue orderQueue;

    @BeforeEach
    void setUp() {
        orderQueue = new OrderQueue();
    }

    private OrderRequest requestForSymbol(String symbol) {
        return OrderRequest.builder()
                .instrumentToken(256265L)
                .tradingSymbol(symbol)
                .exchange("NFO")
                .side(OrderSide.BUY)
                .type(OrderType.MARKET)
                .quantity(50)
                .build();
    }

    @Nested
    @DisplayName("Priority Ordering")
    class PriorityOrdering {

        @Test
        @DisplayName("KILL_SWITCH dequeues before MANUAL even if enqueued later")
        void killSwitchDequeuesBeforeManual() {
            orderQueue.enqueue(requestForSymbol("MANUAL-ORDER"), OrderPriority.MANUAL);
            orderQueue.enqueue(requestForSymbol("KILL-ORDER"), OrderPriority.KILL_SWITCH);

            PrioritizedOrder first = orderQueue.poll();
            PrioritizedOrder second = orderQueue.poll();

            assertThat(first.getPriority()).isEqualTo(OrderPriority.KILL_SWITCH);
            assertThat(second.getPriority()).isEqualTo(OrderPriority.MANUAL);
        }

        @Test
        @DisplayName("All 6 priority levels dequeue in correct order")
        void allSixPriorityLevelsOrderCorrectly() {
            // Enqueue in reverse priority order
            orderQueue.enqueue(requestForSymbol("MANUAL"), OrderPriority.MANUAL);
            orderQueue.enqueue(requestForSymbol("ENTRY"), OrderPriority.STRATEGY_ENTRY);
            orderQueue.enqueue(requestForSymbol("ADJUST"), OrderPriority.STRATEGY_ADJUSTMENT);
            orderQueue.enqueue(requestForSymbol("EXIT"), OrderPriority.STRATEGY_EXIT);
            orderQueue.enqueue(requestForSymbol("RISK"), OrderPriority.RISK_EXIT);
            orderQueue.enqueue(requestForSymbol("KILL"), OrderPriority.KILL_SWITCH);

            List<OrderPriority> dequeueOrder = new ArrayList<>();
            PrioritizedOrder order;
            while ((order = orderQueue.poll()) != null) {
                dequeueOrder.add(order.getPriority());
            }

            assertThat(dequeueOrder)
                    .containsExactly(
                            OrderPriority.KILL_SWITCH,
                            OrderPriority.RISK_EXIT,
                            OrderPriority.STRATEGY_EXIT,
                            OrderPriority.STRATEGY_ADJUSTMENT,
                            OrderPriority.STRATEGY_ENTRY,
                            OrderPriority.MANUAL);
        }

        @Test
        @DisplayName("RISK_EXIT dequeues before STRATEGY_EXIT")
        void riskExitBeforeStrategyExit() {
            orderQueue.enqueue(requestForSymbol("STRATEGY-EXIT"), OrderPriority.STRATEGY_EXIT);
            orderQueue.enqueue(requestForSymbol("RISK-EXIT"), OrderPriority.RISK_EXIT);

            PrioritizedOrder first = orderQueue.poll();
            assertThat(first.getPriority()).isEqualTo(OrderPriority.RISK_EXIT);
        }
    }

    @Nested
    @DisplayName("FIFO Within Same Priority")
    class FifoWithinSamePriority {

        @Test
        @DisplayName("Orders at same priority dequeue in FIFO order")
        void samePriorityDequeuesInFifo() {
            orderQueue.enqueue(requestForSymbol("FIRST"), OrderPriority.STRATEGY_ENTRY);
            orderQueue.enqueue(requestForSymbol("SECOND"), OrderPriority.STRATEGY_ENTRY);
            orderQueue.enqueue(requestForSymbol("THIRD"), OrderPriority.STRATEGY_ENTRY);

            PrioritizedOrder first = orderQueue.poll();
            PrioritizedOrder second = orderQueue.poll();
            PrioritizedOrder third = orderQueue.poll();

            assertThat(first.getOrderRequest().getTradingSymbol()).isEqualTo("FIRST");
            assertThat(second.getOrderRequest().getTradingSymbol()).isEqualTo("SECOND");
            assertThat(third.getOrderRequest().getTradingSymbol()).isEqualTo("THIRD");
        }

        @Test
        @DisplayName("Sequence numbers increase monotonically")
        void sequenceNumbersIncrease() {
            orderQueue.enqueue(requestForSymbol("A"), OrderPriority.MANUAL);
            orderQueue.enqueue(requestForSymbol("B"), OrderPriority.MANUAL);

            PrioritizedOrder first = orderQueue.poll();
            PrioritizedOrder second = orderQueue.poll();

            assertThat(first.getSequenceNumber()).isLessThan(second.getSequenceNumber());
        }
    }

    @Nested
    @DisplayName("Blocking Take")
    class BlockingTake {

        @Test
        @DisplayName("take() blocks on empty queue and unblocks on enqueue")
        void takeBlocksAndUnblocks() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<PrioritizedOrder> result = new AtomicReference<>();

            Thread consumer = new Thread(() -> {
                try {
                    result.set(orderQueue.take());
                    latch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            consumer.start();

            // Give the consumer time to block
            Thread.sleep(50);
            assertThat(result.get()).isNull();

            // Enqueue an order -- consumer should unblock
            orderQueue.enqueue(requestForSymbol("UNBLOCK"), OrderPriority.MANUAL);

            boolean completed = latch.await(2, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(result.get()).isNotNull();
            assertThat(result.get().getOrderRequest().getTradingSymbol()).isEqualTo("UNBLOCK");
        }
    }

    @Nested
    @DisplayName("Queue Metrics")
    class QueueMetrics {

        @Test
        @DisplayName("Size reflects pending orders")
        void sizeReflectsPendingOrders() {
            assertThat(orderQueue.size()).isEqualTo(0);
            assertThat(orderQueue.isEmpty()).isTrue();

            orderQueue.enqueue(requestForSymbol("A"), OrderPriority.MANUAL);
            orderQueue.enqueue(requestForSymbol("B"), OrderPriority.MANUAL);

            assertThat(orderQueue.size()).isEqualTo(2);
            assertThat(orderQueue.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("Size decreases after poll")
        void sizeDecreasesAfterPoll() {
            orderQueue.enqueue(requestForSymbol("A"), OrderPriority.MANUAL);
            orderQueue.poll();

            assertThat(orderQueue.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("poll returns null on empty queue")
        void pollReturnsNullOnEmpty() {
            assertThat(orderQueue.poll()).isNull();
        }
    }

    @Nested
    @DisplayName("Clear")
    class Clear {

        @Test
        @DisplayName("clear removes all orders")
        void clearRemovesAllOrders() {
            orderQueue.enqueue(requestForSymbol("A"), OrderPriority.MANUAL);
            orderQueue.enqueue(requestForSymbol("B"), OrderPriority.KILL_SWITCH);

            orderQueue.clear();

            assertThat(orderQueue.size()).isEqualTo(0);
            assertThat(orderQueue.poll()).isNull();
        }
    }

    @Nested
    @DisplayName("Enqueue Metadata")
    class EnqueueMetadata {

        @Test
        @DisplayName("Enqueued order has enqueuedAt timestamp")
        void enqueuedOrderHasTimestamp() {
            long before = System.currentTimeMillis();
            orderQueue.enqueue(requestForSymbol("A"), OrderPriority.MANUAL);
            long after = System.currentTimeMillis();

            PrioritizedOrder order = orderQueue.poll();
            assertThat(order.getEnqueuedAt()).isBetween(before, after);
        }
    }
}
