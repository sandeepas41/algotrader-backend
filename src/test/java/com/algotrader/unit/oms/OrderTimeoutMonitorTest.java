package com.algotrader.unit.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.oms.OrderTimeoutMonitor;
import com.algotrader.repository.redis.OrderRedisRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OrderTimeoutMonitor covering timeout detection per order type,
 * SL session-end alignment, and timeout handling (cancel + events).
 */
class OrderTimeoutMonitorTest {

    private OrderRedisRepository orderRedisRepository;
    private BrokerGateway brokerGateway;
    private TradingCalendarService tradingCalendarService;
    private EventPublisherHelper eventPublisherHelper;
    private OrderTimeoutMonitor orderTimeoutMonitor;

    @BeforeEach
    void setUp() {
        orderRedisRepository = mock(OrderRedisRepository.class);
        brokerGateway = mock(BrokerGateway.class);
        tradingCalendarService = mock(TradingCalendarService.class);
        eventPublisherHelper = mock(EventPublisherHelper.class);

        orderTimeoutMonitor = new OrderTimeoutMonitor(
                orderRedisRepository, brokerGateway, tradingCalendarService, eventPublisherHelper);

        // Default: 120 minutes to close
        when(tradingCalendarService.getMinutesToClose()).thenReturn(120L);
    }

    private Order orderOfType(OrderType type, LocalDateTime placedAt) {
        return Order.builder()
                .id("ORD-1")
                .brokerOrderId("KT-001")
                .instrumentToken(256265L)
                .tradingSymbol("NIFTY24FEB22000CE")
                .exchange("NFO")
                .side(OrderSide.BUY)
                .type(type)
                .quantity(50)
                .status(OrderStatus.OPEN)
                .strategyId("STR1")
                .correlationId("COR-001")
                .placedAt(placedAt)
                .build();
    }

    @Nested
    @DisplayName("LIMIT Order Timeout")
    class LimitOrderTimeout {

        @Test
        @DisplayName("LIMIT order times out at 30 seconds")
        void limitTimesOutAt30s() {
            LocalDateTime now = LocalDateTime.now();
            Order order = orderOfType(OrderType.LIMIT, now.minusSeconds(31));

            assertThat(orderTimeoutMonitor.isTimedOut(order, now)).isTrue();
        }

        @Test
        @DisplayName("LIMIT order does not time out before 30 seconds")
        void limitDoesNotTimeOutBefore30s() {
            LocalDateTime now = LocalDateTime.now();
            Order order = orderOfType(OrderType.LIMIT, now.minusSeconds(20));

            assertThat(orderTimeoutMonitor.isTimedOut(order, now)).isFalse();
        }
    }

    @Nested
    @DisplayName("MARKET Order Timeout")
    class MarketOrderTimeout {

        @Test
        @DisplayName("MARKET order times out at 10 seconds")
        void marketTimesOutAt10s() {
            LocalDateTime now = LocalDateTime.now();
            Order order = orderOfType(OrderType.MARKET, now.minusSeconds(11));

            assertThat(orderTimeoutMonitor.isTimedOut(order, now)).isTrue();
        }

        @Test
        @DisplayName("MARKET order does not time out before 10 seconds")
        void marketDoesNotTimeOutBefore10s() {
            LocalDateTime now = LocalDateTime.now();
            Order order = orderOfType(OrderType.MARKET, now.minusSeconds(5));

            assertThat(orderTimeoutMonitor.isTimedOut(order, now)).isFalse();
        }
    }

    @Nested
    @DisplayName("SL/SL_M Session-End Timeout")
    class SlSessionEndTimeout {

        @Test
        @DisplayName("SL order timeout aligns with session end")
        void slTimeoutAlignsWithSessionEnd() {
            // 60 minutes to close
            when(tradingCalendarService.getMinutesToClose()).thenReturn(60L);
            LocalDateTime now = LocalDateTime.now();
            Order order = orderOfType(OrderType.SL, now.minusMinutes(30));

            Duration timeout = orderTimeoutMonitor.getTimeoutForOrder(order, now);

            assertThat(timeout).isEqualTo(Duration.ofMinutes(60));
        }

        @Test
        @DisplayName("SL_M order timeout aligns with session end")
        void slmTimeoutAlignsWithSessionEnd() {
            when(tradingCalendarService.getMinutesToClose()).thenReturn(90L);
            LocalDateTime now = LocalDateTime.now();
            Order order = orderOfType(OrderType.SL_M, now.minusMinutes(10));

            Duration timeout = orderTimeoutMonitor.getTimeoutForOrder(order, now);

            assertThat(timeout).isEqualTo(Duration.ofMinutes(90));
        }

        @Test
        @DisplayName("SL order times out immediately when market is closed")
        void slTimesOutWhenMarketClosed() {
            when(tradingCalendarService.getMinutesToClose()).thenReturn(0L);
            LocalDateTime now = LocalDateTime.now();
            Order order = orderOfType(OrderType.SL, now.minusMinutes(5));

            assertThat(orderTimeoutMonitor.isTimedOut(order, now)).isTrue();
        }

        @Test
        @DisplayName("SL order does not time out during trading hours")
        void slDoesNotTimeOutDuringTradingHours() {
            // 120 minutes to close, order placed 30 minutes ago
            when(tradingCalendarService.getMinutesToClose()).thenReturn(120L);
            LocalDateTime now = LocalDateTime.now();
            Order order = orderOfType(OrderType.SL, now.minusMinutes(30));

            assertThat(orderTimeoutMonitor.isTimedOut(order, now)).isFalse();
        }
    }

    @Nested
    @DisplayName("Timeout Handling")
    class TimeoutHandling {

        @Test
        @DisplayName("Timed-out order is cancelled via broker")
        void timedOutOrderCancelled() {
            LocalDateTime now = LocalDateTime.now();
            Order order = orderOfType(OrderType.LIMIT, now.minusSeconds(31));

            when(orderRedisRepository.findPending()).thenReturn(List.of(order));

            orderTimeoutMonitor.checkTimeouts();

            verify(brokerGateway).cancelOrder("KT-001");
        }

        @Test
        @DisplayName("Timed-out order status set to CANCELLED in Redis")
        void timedOutOrderStatusCancelled() {
            LocalDateTime now = LocalDateTime.now();
            Order order = orderOfType(OrderType.LIMIT, now.minusSeconds(31));

            when(orderRedisRepository.findPending()).thenReturn(List.of(order));

            orderTimeoutMonitor.checkTimeouts();

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            verify(orderRedisRepository).save(order);
        }

        @Test
        @DisplayName("Timed-out order publishes CANCELLED event")
        void timedOutOrderPublishesCancelledEvent() {
            LocalDateTime now = LocalDateTime.now();
            Order order = orderOfType(OrderType.LIMIT, now.minusSeconds(31));

            when(orderRedisRepository.findPending()).thenReturn(List.of(order));

            orderTimeoutMonitor.checkTimeouts();

            verify(eventPublisherHelper).publishOrderCancelled(any(), eq(order), eq(OrderStatus.OPEN));
        }

        @Test
        @DisplayName("Non-timed-out orders are not cancelled")
        void nonTimedOutOrdersNotCancelled() {
            LocalDateTime now = LocalDateTime.now();
            Order order = orderOfType(OrderType.LIMIT, now.minusSeconds(10));

            when(orderRedisRepository.findPending()).thenReturn(List.of(order));

            orderTimeoutMonitor.checkTimeouts();

            verify(brokerGateway, never()).cancelOrder(anyString());
        }

        @Test
        @DisplayName("Empty pending list does nothing")
        void emptyPendingListDoesNothing() {
            when(orderRedisRepository.findPending()).thenReturn(Collections.emptyList());

            orderTimeoutMonitor.checkTimeouts();

            verify(brokerGateway, never()).cancelOrder(anyString());
        }

        @Test
        @DisplayName("Timed-out order logs decision event")
        void timedOutOrderLogsDecision() {
            LocalDateTime now = LocalDateTime.now();
            Order order = orderOfType(OrderType.LIMIT, now.minusSeconds(31));

            when(orderRedisRepository.findPending()).thenReturn(List.of(order));

            orderTimeoutMonitor.checkTimeouts();

            verify(eventPublisherHelper).publishDecision(any(), eq("ORDER"), anyString(), eq("STR1"), any(Map.class));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Order with null placedAt does not time out")
        void nullPlacedAtDoesNotTimeout() {
            Order order = Order.builder()
                    .id("ORD-1")
                    .brokerOrderId("KT-001")
                    .type(OrderType.LIMIT)
                    .status(OrderStatus.OPEN)
                    .placedAt(null)
                    .build();

            assertThat(orderTimeoutMonitor.isTimedOut(order, LocalDateTime.now()))
                    .isFalse();
        }
    }
}
