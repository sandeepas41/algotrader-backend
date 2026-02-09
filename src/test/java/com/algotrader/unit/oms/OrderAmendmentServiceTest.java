package com.algotrader.unit.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.domain.enums.AmendmentStatus;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.oms.OrderAmendmentResult;
import com.algotrader.oms.OrderAmendmentService;
import com.algotrader.oms.OrderModification;
import com.algotrader.repository.redis.OrderRedisRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OrderAmendmentService covering the amendment state machine,
 * validation rules, broker communication, and error handling.
 */
class OrderAmendmentServiceTest {

    private OrderRedisRepository orderRedisRepository;
    private BrokerGateway brokerGateway;
    private EventPublisherHelper eventPublisherHelper;
    private OrderAmendmentService orderAmendmentService;

    @BeforeEach
    void setUp() {
        orderRedisRepository = mock(OrderRedisRepository.class);
        brokerGateway = mock(BrokerGateway.class);
        eventPublisherHelper = mock(EventPublisherHelper.class);

        orderAmendmentService = new OrderAmendmentService(orderRedisRepository, brokerGateway, eventPublisherHelper);
    }

    private Order openOrder() {
        return Order.builder()
                .id("ORD-1")
                .brokerOrderId("KT-001")
                .instrumentToken(256265L)
                .tradingSymbol("NIFTY24FEB22000CE")
                .exchange("NFO")
                .side(OrderSide.BUY)
                .type(OrderType.LIMIT)
                .quantity(50)
                .price(BigDecimal.valueOf(150.00))
                .status(OrderStatus.OPEN)
                .amendmentStatus(AmendmentStatus.NONE)
                .filledQuantity(0)
                .strategyId("STR1")
                .correlationId("COR-001")
                .placedAt(LocalDateTime.now().minusSeconds(5))
                .build();
    }

    @Nested
    @DisplayName("Successful Amendment")
    class SuccessfulAmendment {

        @Test
        @DisplayName("Price modification succeeds and updates order")
        void priceModificationSucceeds() {
            Order order = openOrder();
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderModification modification = OrderModification.builder()
                    .price(BigDecimal.valueOf(160.00))
                    .build();

            OrderAmendmentResult result = orderAmendmentService.modifyOrder("ORD-1", modification);

            assertThat(result.isAccepted()).isTrue();
            assertThat(result.getOrderId()).isEqualTo("ORD-1");
            assertThat(order.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(160.00));
        }

        @Test
        @DisplayName("Quantity modification succeeds")
        void quantityModificationSucceeds() {
            Order order = openOrder();
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderModification modification =
                    OrderModification.builder().quantity(75).build();

            OrderAmendmentResult result = orderAmendmentService.modifyOrder("ORD-1", modification);

            assertThat(result.isAccepted()).isTrue();
            assertThat(order.getQuantity()).isEqualTo(75);
        }

        @Test
        @DisplayName("Trigger price modification succeeds")
        void triggerPriceModificationSucceeds() {
            Order order = openOrder();
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderModification modification = OrderModification.builder()
                    .triggerPrice(BigDecimal.valueOf(145.00))
                    .build();

            OrderAmendmentResult result = orderAmendmentService.modifyOrder("ORD-1", modification);

            assertThat(result.isAccepted()).isTrue();
            assertThat(order.getTriggerPrice()).isEqualByComparingTo(BigDecimal.valueOf(145.00));
        }

        @Test
        @DisplayName("Successful amendment calls broker modifyOrder")
        void successfulAmendmentCallsBroker() {
            Order order = openOrder();
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderModification modification = OrderModification.builder()
                    .price(BigDecimal.valueOf(160.00))
                    .build();

            orderAmendmentService.modifyOrder("ORD-1", modification);

            verify(brokerGateway).modifyOrder(eq("KT-001"), any(Order.class));
        }

        @Test
        @DisplayName("Successful amendment publishes modified event")
        void successfulAmendmentPublishesEvent() {
            Order order = openOrder();
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderModification modification = OrderModification.builder()
                    .price(BigDecimal.valueOf(160.00))
                    .build();

            orderAmendmentService.modifyOrder("ORD-1", modification);

            verify(eventPublisherHelper).publishOrderModified(any(), any(Order.class));
        }

        @Test
        @DisplayName("Amendment status resets to NONE after successful modification")
        void amendmentStatusResetsAfterSuccess() {
            Order order = openOrder();
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderModification modification = OrderModification.builder()
                    .price(BigDecimal.valueOf(160.00))
                    .build();

            orderAmendmentService.modifyOrder("ORD-1", modification);

            assertThat(order.getAmendmentStatus()).isEqualTo(AmendmentStatus.NONE);
        }
    }

    @Nested
    @DisplayName("State Validation")
    class StateValidation {

        @Test
        @DisplayName("Rejects modification for CANCELLED order")
        void rejectsCancelledOrder() {
            Order order = openOrder();
            order.setStatus(OrderStatus.CANCELLED);
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderAmendmentResult result = orderAmendmentService.modifyOrder(
                    "ORD-1",
                    OrderModification.builder()
                            .price(BigDecimal.valueOf(160.00))
                            .build());

            assertThat(result.isAccepted()).isFalse();
            assertThat(result.getRejectionReason()).contains("not in a modifiable state");
        }

        @Test
        @DisplayName("Rejects modification for COMPLETE order")
        void rejectsCompleteOrder() {
            Order order = openOrder();
            order.setStatus(OrderStatus.COMPLETE);
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderAmendmentResult result = orderAmendmentService.modifyOrder(
                    "ORD-1",
                    OrderModification.builder()
                            .price(BigDecimal.valueOf(160.00))
                            .build());

            assertThat(result.isAccepted()).isFalse();
        }

        @Test
        @DisplayName("Allows modification for TRIGGER_PENDING order")
        void allowsTriggerPendingOrder() {
            Order order = openOrder();
            order.setStatus(OrderStatus.TRIGGER_PENDING);
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderAmendmentResult result = orderAmendmentService.modifyOrder(
                    "ORD-1",
                    OrderModification.builder()
                            .triggerPrice(BigDecimal.valueOf(145.00))
                            .build());

            assertThat(result.isAccepted()).isTrue();
        }

        @Test
        @DisplayName("Rejects when amendment already in progress (MODIFY_REQUESTED)")
        void rejectsWhenModifyRequested() {
            Order order = openOrder();
            order.setAmendmentStatus(AmendmentStatus.MODIFY_REQUESTED);
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderAmendmentResult result = orderAmendmentService.modifyOrder(
                    "ORD-1",
                    OrderModification.builder()
                            .price(BigDecimal.valueOf(160.00))
                            .build());

            assertThat(result.isAccepted()).isFalse();
            assertThat(result.getRejectionReason()).contains("Amendment already in progress");
        }

        @Test
        @DisplayName("Rejects when amendment already in progress (MODIFY_SENT)")
        void rejectsWhenModifySent() {
            Order order = openOrder();
            order.setAmendmentStatus(AmendmentStatus.MODIFY_SENT);
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderAmendmentResult result = orderAmendmentService.modifyOrder(
                    "ORD-1",
                    OrderModification.builder()
                            .price(BigDecimal.valueOf(160.00))
                            .build());

            assertThat(result.isAccepted()).isFalse();
        }

        @Test
        @DisplayName("Order not found returns rejected result")
        void orderNotFound() {
            when(orderRedisRepository.findById("ORD-MISSING")).thenReturn(Optional.empty());

            OrderAmendmentResult result = orderAmendmentService.modifyOrder(
                    "ORD-MISSING",
                    OrderModification.builder()
                            .price(BigDecimal.valueOf(160.00))
                            .build());

            assertThat(result.isAccepted()).isFalse();
            assertThat(result.getRejectionReason()).contains("Order not found");
        }
    }

    @Nested
    @DisplayName("Parameter Validation")
    class ParameterValidation {

        @Test
        @DisplayName("Rejects when no modification fields are set")
        void rejectsEmptyModification() {
            Order order = openOrder();
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderAmendmentResult result = orderAmendmentService.modifyOrder(
                    "ORD-1", OrderModification.builder().build());

            assertThat(result.isAccepted()).isFalse();
            assertThat(result.getRejectionReason()).contains("At least one modification field");
        }

        @Test
        @DisplayName("Rejects negative price")
        void rejectsNegativePrice() {
            Order order = openOrder();
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderAmendmentResult result = orderAmendmentService.modifyOrder(
                    "ORD-1",
                    OrderModification.builder().price(BigDecimal.valueOf(-10)).build());

            assertThat(result.isAccepted()).isFalse();
            assertThat(result.getRejectionReason()).contains("Invalid modification price");
        }

        @Test
        @DisplayName("Rejects zero price")
        void rejectsZeroPrice() {
            Order order = openOrder();
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderAmendmentResult result = orderAmendmentService.modifyOrder(
                    "ORD-1", OrderModification.builder().price(BigDecimal.ZERO).build());

            assertThat(result.isAccepted()).isFalse();
        }

        @Test
        @DisplayName("Rejects quantity less than filled quantity")
        void rejectsQuantityLessThanFilled() {
            Order order = openOrder();
            order.setFilledQuantity(30);
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderAmendmentResult result = orderAmendmentService.modifyOrder(
                    "ORD-1", OrderModification.builder().quantity(20).build());

            assertThat(result.isAccepted()).isFalse();
            assertThat(result.getRejectionReason()).contains("must exceed already filled quantity");
        }

        @Test
        @DisplayName("Rejects zero quantity")
        void rejectsZeroQuantity() {
            Order order = openOrder();
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderAmendmentResult result = orderAmendmentService.modifyOrder(
                    "ORD-1", OrderModification.builder().quantity(0).build());

            assertThat(result.isAccepted()).isFalse();
            assertThat(result.getRejectionReason()).contains("Invalid modification quantity");
        }

        @Test
        @DisplayName("Rejects negative trigger price")
        void rejectsNegativeTriggerPrice() {
            Order order = openOrder();
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            OrderAmendmentResult result = orderAmendmentService.modifyOrder(
                    "ORD-1",
                    OrderModification.builder()
                            .triggerPrice(BigDecimal.valueOf(-5))
                            .build());

            assertThat(result.isAccepted()).isFalse();
            assertThat(result.getRejectionReason()).contains("Invalid trigger price");
        }
    }

    @Nested
    @DisplayName("Broker Failure")
    class BrokerFailure {

        @Test
        @DisplayName("Broker rejection sets MODIFY_REJECTED status")
        void brokerRejectionSetsStatus() {
            Order order = openOrder();
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));
            doThrow(new RuntimeException("Exchange rejected modification"))
                    .when(brokerGateway)
                    .modifyOrder(anyString(), any(Order.class));

            OrderAmendmentResult result = orderAmendmentService.modifyOrder(
                    "ORD-1",
                    OrderModification.builder()
                            .price(BigDecimal.valueOf(160.00))
                            .build());

            assertThat(result.isAccepted()).isFalse();
            assertThat(result.getRejectionReason()).isEqualTo("Exchange rejected modification");
            assertThat(order.getAmendmentStatus()).isEqualTo(AmendmentStatus.MODIFY_REJECTED);
            assertThat(order.getAmendmentRejectReason()).isEqualTo("Exchange rejected modification");
        }

        @Test
        @DisplayName("Broker failure does not publish modified event")
        void brokerFailureDoesNotPublishEvent() {
            Order order = openOrder();
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));
            doThrow(new RuntimeException("Network error"))
                    .when(brokerGateway)
                    .modifyOrder(anyString(), any(Order.class));

            orderAmendmentService.modifyOrder(
                    "ORD-1",
                    OrderModification.builder()
                            .price(BigDecimal.valueOf(160.00))
                            .build());

            verify(eventPublisherHelper, never()).publishOrderModified(any(), any(Order.class));
        }

        @Test
        @DisplayName("Broker failure does not change original order price")
        void brokerFailurePreservesOriginalPrice() {
            Order order = openOrder();
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));
            doThrow(new RuntimeException("Rejected")).when(brokerGateway).modifyOrder(anyString(), any(Order.class));

            orderAmendmentService.modifyOrder(
                    "ORD-1",
                    OrderModification.builder()
                            .price(BigDecimal.valueOf(999.00))
                            .build());

            // Original price should remain unchanged because applyModification happens after broker call
            assertThat(order.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(150.00));
        }
    }

    @Nested
    @DisplayName("Decision Logging")
    class DecisionLogging {

        @Test
        @DisplayName("Successful amendment logs decision")
        void successfulAmendmentLogsDecision() {
            Order order = openOrder();
            when(orderRedisRepository.findById("ORD-1")).thenReturn(Optional.of(order));

            orderAmendmentService.modifyOrder(
                    "ORD-1",
                    OrderModification.builder()
                            .price(BigDecimal.valueOf(160.00))
                            .build());

            verify(eventPublisherHelper).publishDecision(any(), eq("ORDER"), anyString(), eq("STR1"), any(Map.class));
        }
    }
}
