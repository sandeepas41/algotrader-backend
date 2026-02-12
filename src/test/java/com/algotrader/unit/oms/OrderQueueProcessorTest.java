package com.algotrader.unit.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.oms.IdempotencyService;
import com.algotrader.oms.OrderQueue;
import com.algotrader.oms.OrderQueueProcessor;
import com.algotrader.oms.OrderRequest;
import com.algotrader.oms.PrioritizedOrder;
import com.algotrader.repository.redis.OrderRedisRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for OrderQueueProcessor covering order-to-domain conversion,
 * successful placement, broker failure handling, lifecycle management,
 * and shutdown drain behavior.
 */
class OrderQueueProcessorTest {

    private OrderQueue orderQueue;
    private BrokerGateway brokerGateway;
    private EventPublisherHelper eventPublisherHelper;
    private IdempotencyService idempotencyService;
    private OrderRedisRepository orderRedisRepository;
    private OrderQueueProcessor orderQueueProcessor;

    @BeforeEach
    void setUp() {
        orderQueue = mock(OrderQueue.class);
        brokerGateway = mock(BrokerGateway.class);
        eventPublisherHelper = mock(EventPublisherHelper.class);
        idempotencyService = mock(IdempotencyService.class);
        orderRedisRepository = mock(OrderRedisRepository.class);

        orderQueueProcessor = new OrderQueueProcessor(
                orderQueue, brokerGateway, eventPublisherHelper, idempotencyService, orderRedisRepository);
    }

    private PrioritizedOrder samplePrioritizedOrder() {
        OrderRequest orderRequest = OrderRequest.builder()
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

        return PrioritizedOrder.builder()
                .orderRequest(orderRequest)
                .priority(OrderPriority.STRATEGY_ENTRY)
                .sequenceNumber(1L)
                .enqueuedAt(System.currentTimeMillis())
                .build();
    }

    @Nested
    @DisplayName("Successful Order Processing")
    class SuccessfulProcessing {

        @Test
        @DisplayName("Placed order gets broker order ID and OPEN status")
        void placedOrderGetsBrokerIdAndOpenStatus() {
            when(brokerGateway.placeOrder(any(Order.class))).thenReturn("KT-12345");

            orderQueueProcessor.processOrder(samplePrioritizedOrder());

            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            verify(eventPublisherHelper).publishOrderPlaced(any(), orderCaptor.capture());

            Order placedOrder = orderCaptor.getValue();
            assertThat(placedOrder.getBrokerOrderId()).isEqualTo("KT-12345");
            assertThat(placedOrder.getStatus()).isEqualTo(OrderStatus.OPEN);
            assertThat(placedOrder.getPlacedAt()).isNotNull();
        }

        @Test
        @DisplayName("Placed order has non-null UUID id")
        void placedOrderHasNonNullId() {
            when(brokerGateway.placeOrder(any(Order.class))).thenReturn("KT-12345");

            orderQueueProcessor.processOrder(samplePrioritizedOrder());

            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            verify(eventPublisherHelper).publishOrderPlaced(any(), orderCaptor.capture());

            Order placedOrder = orderCaptor.getValue();
            assertThat(placedOrder.getId()).isNotNull();
            assertThat(placedOrder.getId()).hasSize(36); // UUID format
        }

        @Test
        @DisplayName("Placed order is saved to Redis before event is published")
        void placedOrderSavedToRedis() {
            when(brokerGateway.placeOrder(any(Order.class))).thenReturn("KT-12345");

            orderQueueProcessor.processOrder(samplePrioritizedOrder());

            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            verify(orderRedisRepository).save(orderCaptor.capture());

            Order savedOrder = orderCaptor.getValue();
            assertThat(savedOrder.getBrokerOrderId()).isEqualTo("KT-12345");
            assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.OPEN);
        }

        @Test
        @DisplayName("Successful placement publishes ORDER_PLACED event")
        void successfulPlacementPublishesEvent() {
            when(brokerGateway.placeOrder(any(Order.class))).thenReturn("KT-12345");

            orderQueueProcessor.processOrder(samplePrioritizedOrder());

            verify(eventPublisherHelper).publishOrderPlaced(any(), any(Order.class));
        }

        @Test
        @DisplayName("Successful placement marks idempotency key")
        void successfulPlacementMarksIdempotency() {
            when(brokerGateway.placeOrder(any(Order.class))).thenReturn("KT-12345");

            PrioritizedOrder prioritizedOrder = samplePrioritizedOrder();
            orderQueueProcessor.processOrder(prioritizedOrder);

            verify(idempotencyService).markProcessed(prioritizedOrder.getOrderRequest());
        }
    }

    @Nested
    @DisplayName("Order-to-Domain Conversion")
    class OrderConversion {

        @Test
        @DisplayName("OrderRequest fields map correctly to Order domain model")
        void orderRequestFieldsMapToOrder() {
            when(brokerGateway.placeOrder(any(Order.class))).thenReturn("KT-99");

            orderQueueProcessor.processOrder(samplePrioritizedOrder());

            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            verify(brokerGateway).placeOrder(orderCaptor.capture());

            Order order = orderCaptor.getValue();
            assertThat(order.getInstrumentToken()).isEqualTo(256265L);
            assertThat(order.getTradingSymbol()).isEqualTo("NIFTY24FEB22000CE");
            assertThat(order.getExchange()).isEqualTo("NFO");
            assertThat(order.getSide()).isEqualTo(OrderSide.SELL);
            assertThat(order.getType()).isEqualTo(OrderType.LIMIT);
            assertThat(order.getProduct()).isEqualTo("NRML");
            assertThat(order.getQuantity()).isEqualTo(50);
            assertThat(order.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(150.00));
            assertThat(order.getStrategyId()).isEqualTo("STR1");
            assertThat(order.getCorrelationId()).isEqualTo("COR-001");
        }

        @Test
        @DisplayName("Order starts with PENDING status before broker placement")
        void orderStartsWithPendingStatus() {
            when(brokerGateway.placeOrder(any(Order.class))).thenAnswer(invocation -> {
                Order order = invocation.getArgument(0);
                assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
                return "KT-001";
            });

            orderQueueProcessor.processOrder(samplePrioritizedOrder());

            verify(brokerGateway).placeOrder(any(Order.class));
        }
    }

    @Nested
    @DisplayName("Broker Failure Handling")
    class BrokerFailure {

        @Test
        @DisplayName("Broker exception sets REJECTED status with reason")
        void brokerExceptionSetsRejectedStatus() {
            doThrow(new RuntimeException("Insufficient margin"))
                    .when(brokerGateway)
                    .placeOrder(any(Order.class));

            orderQueueProcessor.processOrder(samplePrioritizedOrder());

            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            verify(eventPublisherHelper).publishOrderRejected(any(), orderCaptor.capture());

            Order rejectedOrder = orderCaptor.getValue();
            assertThat(rejectedOrder.getStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(rejectedOrder.getRejectionReason()).isEqualTo("Insufficient margin");
        }

        @Test
        @DisplayName("Broker failure publishes ORDER_REJECTED event")
        void brokerFailurePublishesRejectedEvent() {
            doThrow(new RuntimeException("Network timeout")).when(brokerGateway).placeOrder(any(Order.class));

            orderQueueProcessor.processOrder(samplePrioritizedOrder());

            verify(eventPublisherHelper).publishOrderRejected(any(), any(Order.class));
            verify(eventPublisherHelper, never()).publishOrderPlaced(any(), any(Order.class));
        }

        @Test
        @DisplayName("Broker failure does not mark idempotency key")
        void brokerFailureDoesNotMarkIdempotency() {
            doThrow(new RuntimeException("Broker down")).when(brokerGateway).placeOrder(any(Order.class));

            orderQueueProcessor.processOrder(samplePrioritizedOrder());

            verify(idempotencyService, never()).markProcessed(any());
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("isRunning is false before start")
        void isRunningFalseBeforeStart() {
            assertThat(orderQueueProcessor.isRunning()).isFalse();
        }

        @Test
        @DisplayName("start sets isRunning to true")
        void startSetsRunningTrue() throws InterruptedException {
            // Mock take() to block so the thread doesn't exit immediately
            when(orderQueue.take()).thenAnswer(invocation -> {
                Thread.sleep(5000);
                return null;
            });

            orderQueueProcessor.start();
            Thread.sleep(50); // give thread time to start

            assertThat(orderQueueProcessor.isRunning()).isTrue();

            orderQueueProcessor.stop();
        }

        @Test
        @DisplayName("stop sets isRunning to false")
        void stopSetsRunningFalse() throws InterruptedException {
            when(orderQueue.take()).thenAnswer(invocation -> {
                Thread.sleep(5000);
                return null;
            });
            when(orderQueue.poll()).thenReturn(null);

            orderQueueProcessor.start();
            Thread.sleep(50);

            orderQueueProcessor.stop();
            Thread.sleep(50);

            assertThat(orderQueueProcessor.isRunning()).isFalse();
        }

        @Test
        @DisplayName("Double start is idempotent")
        void doubleStartIsIdempotent() throws InterruptedException {
            when(orderQueue.take()).thenAnswer(invocation -> {
                Thread.sleep(5000);
                return null;
            });

            orderQueueProcessor.start();
            orderQueueProcessor.start(); // second call should be no-op

            assertThat(orderQueueProcessor.isRunning()).isTrue();

            orderQueueProcessor.stop();
        }

        @Test
        @DisplayName("Double stop is idempotent")
        void doubleStopIsIdempotent() {
            orderQueueProcessor.stop(); // stop without start
            orderQueueProcessor.stop(); // second stop

            assertThat(orderQueueProcessor.isRunning()).isFalse();
        }
    }

    @Nested
    @DisplayName("Shutdown Drain")
    class ShutdownDrain {

        @Test
        @DisplayName("Remaining orders are drained on shutdown")
        void remainingOrdersDrainedOnShutdown() throws InterruptedException {
            PrioritizedOrder order1 = samplePrioritizedOrder();
            PrioritizedOrder order2 = samplePrioritizedOrder();

            // First take() call returns order1, then thread is interrupted during second take()
            when(orderQueue.take()).thenReturn(order1).thenAnswer(invocation -> {
                Thread.sleep(5000);
                return null;
            });

            // poll() returns order2 during drain, then null to stop drain loop
            when(orderQueue.poll()).thenReturn(order2).thenReturn(null);
            when(brokerGateway.placeOrder(any(Order.class))).thenReturn("KT-001");

            orderQueueProcessor.start();
            Thread.sleep(100); // let the first order process

            orderQueueProcessor.stop();
            Thread.sleep(200); // let drain complete

            // order1 processed in loop, order2 processed in drain
            verify(brokerGateway, times(2)).placeOrder(any(Order.class));
        }
    }
}
