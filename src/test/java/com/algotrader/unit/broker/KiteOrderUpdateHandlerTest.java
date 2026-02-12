package com.algotrader.unit.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.broker.KiteOrderUpdateHandler;
import com.algotrader.broker.mapper.KiteOrderMapper;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.reconciliation.PositionReconciliationService;
import com.algotrader.repository.redis.OrderRedisRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link KiteOrderUpdateHandler} covering null safety,
 * status routing, idempotency guards, terminal state protection,
 * and reconciliation failure isolation.
 */
@ExtendWith(MockitoExtension.class)
class KiteOrderUpdateHandlerTest {

    @Mock
    private OrderRedisRepository orderRedisRepository;

    @Mock
    private KiteOrderMapper kiteOrderMapper;

    @Mock
    private EventPublisherHelper eventPublisherHelper;

    @Mock
    private PositionReconciliationService positionReconciliationService;

    private KiteOrderUpdateHandler kiteOrderUpdateHandler;

    @BeforeEach
    void setUp() {
        kiteOrderUpdateHandler = new KiteOrderUpdateHandler(
                orderRedisRepository, kiteOrderMapper, eventPublisherHelper, positionReconciliationService);
    }

    private com.zerodhatech.models.Order kiteOrder(String orderId, String status, String filledQty, String avgPrice) {
        com.zerodhatech.models.Order kiteOrder = new com.zerodhatech.models.Order();
        kiteOrder.orderId = orderId;
        kiteOrder.status = status;
        kiteOrder.filledQuantity = filledQty;
        kiteOrder.averagePrice = avgPrice;
        kiteOrder.statusMessage = "test reason";
        return kiteOrder;
    }

    private Order existingOrder(String id, String brokerOrderId, OrderStatus status, int filledQty) {
        return Order.builder()
                .id(id)
                .brokerOrderId(brokerOrderId)
                .instrumentToken(256265L)
                .tradingSymbol("NIFTY24FEB22000CE")
                .exchange("NFO")
                .side(OrderSide.SELL)
                .type(OrderType.LIMIT)
                .product("NRML")
                .quantity(50)
                .price(BigDecimal.valueOf(150.00))
                .strategyId("STR1")
                .status(status)
                .filledQuantity(filledQty)
                .averageFillPrice(BigDecimal.ZERO)
                .placedAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("Null Safety")
    class NullSafety {

        @Test
        @DisplayName("null order is ignored without exception")
        void nullOrderIgnored() {
            assertThatCode(() -> kiteOrderUpdateHandler.handleOrderUpdate(null)).doesNotThrowAnyException();

            verify(orderRedisRepository, never()).findByBrokerOrderId(any());
        }

        @Test
        @DisplayName("order with null orderId is ignored without exception")
        void nullOrderIdIgnored() {
            com.zerodhatech.models.Order kiteOrder = new com.zerodhatech.models.Order();
            kiteOrder.orderId = null;

            assertThatCode(() -> kiteOrderUpdateHandler.handleOrderUpdate(kiteOrder))
                    .doesNotThrowAnyException();

            verify(orderRedisRepository, never()).findByBrokerOrderId(any());
        }
    }

    @Nested
    @DisplayName("Unknown Order")
    class UnknownOrder {

        @Test
        @DisplayName("unknown broker order ID is ignored")
        void unknownBrokerOrderIdIgnored() {
            com.zerodhatech.models.Order kiteOrder = kiteOrder("KT-UNKNOWN", "COMPLETE", "50", "150.0");
            when(kiteOrderMapper.mapStatus("COMPLETE")).thenReturn(OrderStatus.COMPLETE);
            when(orderRedisRepository.findByBrokerOrderId("KT-UNKNOWN")).thenReturn(Optional.empty());

            kiteOrderUpdateHandler.handleOrderUpdate(kiteOrder);

            verify(eventPublisherHelper, never()).publishOrderFilled(any(), any(), any());
            verify(eventPublisherHelper, never()).publishOrderRejected(any(), any());
        }
    }

    @Nested
    @DisplayName("Terminal State Protection")
    class TerminalStateProtection {

        @Test
        @DisplayName("existing COMPLETE order is not overwritten by new update")
        void terminalOrderNotOverwritten() {
            Order existing = existingOrder("ORD-1", "KT-100", OrderStatus.COMPLETE, 50);
            com.zerodhatech.models.Order kiteOrder = kiteOrder("KT-100", "COMPLETE", "50", "155.0");
            when(kiteOrderMapper.mapStatus("COMPLETE")).thenReturn(OrderStatus.COMPLETE);
            when(orderRedisRepository.findByBrokerOrderId("KT-100")).thenReturn(Optional.of(existing));

            kiteOrderUpdateHandler.handleOrderUpdate(kiteOrder);

            verify(orderRedisRepository, never()).save(any());
            verify(eventPublisherHelper, never()).publishOrderFilled(any(), any(), any());
        }

        @Test
        @DisplayName("existing REJECTED order is not overwritten")
        void rejectedOrderNotOverwritten() {
            Order existing = existingOrder("ORD-2", "KT-200", OrderStatus.REJECTED, 0);
            com.zerodhatech.models.Order kiteOrder = kiteOrder("KT-200", "OPEN", "0", "0");
            when(kiteOrderMapper.mapStatus("OPEN")).thenReturn(OrderStatus.OPEN);
            when(orderRedisRepository.findByBrokerOrderId("KT-200")).thenReturn(Optional.of(existing));

            kiteOrderUpdateHandler.handleOrderUpdate(kiteOrder);

            verify(orderRedisRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Rejection Handling")
    class RejectionHandling {

        @Test
        @DisplayName("REJECTED status sets reason, saves, and publishes event")
        void rejectedOrderPublishesEvent() {
            Order existing = existingOrder("ORD-3", "KT-300", OrderStatus.OPEN, 0);
            com.zerodhatech.models.Order kiteOrder = kiteOrder("KT-300", "REJECTED", "0", "0");
            kiteOrder.statusMessage = "Insufficient margin";
            when(kiteOrderMapper.mapStatus("REJECTED")).thenReturn(OrderStatus.REJECTED);
            when(orderRedisRepository.findByBrokerOrderId("KT-300")).thenReturn(Optional.of(existing));

            kiteOrderUpdateHandler.handleOrderUpdate(kiteOrder);

            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            verify(orderRedisRepository).save(orderCaptor.capture());
            verify(eventPublisherHelper).publishOrderRejected(any(), orderCaptor.capture());

            Order savedOrder = orderCaptor.getAllValues().get(0);
            assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(savedOrder.getRejectionReason()).isEqualTo("Insufficient margin");
            assertThat(savedOrder.getUpdatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Complete (Full Fill) Handling")
    class CompleteHandling {

        @Test
        @DisplayName("COMPLETE with increased filledQty publishes FILLED event and triggers reconcile")
        void completeOrderPublishesFilledEvent() {
            Order existing = existingOrder("ORD-4", "KT-400", OrderStatus.OPEN, 0);
            com.zerodhatech.models.Order kiteOrder = kiteOrder("KT-400", "COMPLETE", "50", "152.50");
            when(kiteOrderMapper.mapStatus("COMPLETE")).thenReturn(OrderStatus.COMPLETE);
            when(orderRedisRepository.findByBrokerOrderId("KT-400")).thenReturn(Optional.of(existing));

            kiteOrderUpdateHandler.handleOrderUpdate(kiteOrder);

            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            verify(orderRedisRepository).save(orderCaptor.capture());
            verify(eventPublisherHelper).publishOrderFilled(any(), orderCaptor.capture(), eq(OrderStatus.OPEN));

            Order filledOrder = orderCaptor.getAllValues().get(0);
            assertThat(filledOrder.getStatus()).isEqualTo(OrderStatus.COMPLETE);
            assertThat(filledOrder.getFilledQuantity()).isEqualTo(50);
            assertThat(filledOrder.getAverageFillPrice()).isEqualByComparingTo(new BigDecimal("152.50"));
            assertThat(filledOrder.getUpdatedAt()).isNotNull();

            verify(positionReconciliationService).reconcile("ORDER_FILL");
        }

        @Test
        @DisplayName("duplicate COMPLETE with same filledQty is ignored (idempotency)")
        void duplicateFillIgnored() {
            Order existing = existingOrder("ORD-5", "KT-500", OrderStatus.OPEN, 50);
            com.zerodhatech.models.Order kiteOrder = kiteOrder("KT-500", "COMPLETE", "50", "152.50");
            when(kiteOrderMapper.mapStatus("COMPLETE")).thenReturn(OrderStatus.COMPLETE);
            when(orderRedisRepository.findByBrokerOrderId("KT-500")).thenReturn(Optional.of(existing));

            kiteOrderUpdateHandler.handleOrderUpdate(kiteOrder);

            verify(orderRedisRepository, never()).save(any());
            verify(eventPublisherHelper, never()).publishOrderFilled(any(), any(), any());
            verify(positionReconciliationService, never()).reconcile(any());
        }
    }

    @Nested
    @DisplayName("Partial Fill Handling")
    class PartialFillHandling {

        @Test
        @DisplayName("non-terminal status with increased filledQty publishes PARTIALLY_FILLED")
        void partialFillPublishesEvent() {
            Order existing = existingOrder("ORD-6", "KT-600", OrderStatus.OPEN, 0);
            com.zerodhatech.models.Order kiteOrder = kiteOrder("KT-600", "OPEN", "25", "151.00");
            when(kiteOrderMapper.mapStatus("OPEN")).thenReturn(OrderStatus.OPEN);
            when(orderRedisRepository.findByBrokerOrderId("KT-600")).thenReturn(Optional.of(existing));

            kiteOrderUpdateHandler.handleOrderUpdate(kiteOrder);

            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            verify(orderRedisRepository).save(orderCaptor.capture());
            verify(eventPublisherHelper)
                    .publishOrderPartiallyFilled(any(), orderCaptor.capture(), eq(OrderStatus.OPEN));

            Order partialOrder = orderCaptor.getAllValues().get(0);
            assertThat(partialOrder.getStatus()).isEqualTo(OrderStatus.PARTIAL);
            assertThat(partialOrder.getFilledQuantity()).isEqualTo(25);
            assertThat(partialOrder.getAverageFillPrice()).isEqualByComparingTo(new BigDecimal("151.00"));

            // Partial fills should NOT trigger position reconciliation
            verify(positionReconciliationService, never()).reconcile(any());
        }
    }

    @Nested
    @DisplayName("No-Fill Status Update")
    class NoFillUpdate {

        @Test
        @DisplayName("non-terminal status with no fill change is ignored")
        void nonTerminalStatusWithNoFillIgnored() {
            Order existing = existingOrder("ORD-7", "KT-700", OrderStatus.OPEN, 0);
            com.zerodhatech.models.Order kiteOrder = kiteOrder("KT-700", "OPEN", "0", "0");
            when(kiteOrderMapper.mapStatus("OPEN")).thenReturn(OrderStatus.OPEN);
            when(orderRedisRepository.findByBrokerOrderId("KT-700")).thenReturn(Optional.of(existing));

            kiteOrderUpdateHandler.handleOrderUpdate(kiteOrder);

            verify(orderRedisRepository, never()).save(any());
            verify(eventPublisherHelper, never()).publishOrderFilled(any(), any(), any());
            verify(eventPublisherHelper, never()).publishOrderPartiallyFilled(any(), any(), any());
            verify(eventPublisherHelper, never()).publishOrderRejected(any(), any());
        }
    }

    @Nested
    @DisplayName("Reconciliation Failure Isolation")
    class ReconciliationFailure {

        @Test
        @DisplayName("reconciliation exception does not propagate to caller")
        void reconciliationFailureDoesNotPropagate() {
            Order existing = existingOrder("ORD-8", "KT-800", OrderStatus.OPEN, 0);
            com.zerodhatech.models.Order kiteOrder = kiteOrder("KT-800", "COMPLETE", "50", "155.00");
            when(kiteOrderMapper.mapStatus("COMPLETE")).thenReturn(OrderStatus.COMPLETE);
            when(orderRedisRepository.findByBrokerOrderId("KT-800")).thenReturn(Optional.of(existing));
            doThrow(new RuntimeException("Kite API down"))
                    .when(positionReconciliationService)
                    .reconcile("ORDER_FILL");

            // Should NOT throw — reconciliation failure is caught internally
            assertThatCode(() -> kiteOrderUpdateHandler.handleOrderUpdate(kiteOrder))
                    .doesNotThrowAnyException();

            // Order should still be saved and event published despite reconciliation failure
            verify(orderRedisRepository).save(any());
            verify(eventPublisherHelper).publishOrderFilled(any(), any(), eq(OrderStatus.OPEN));
        }
    }
}
