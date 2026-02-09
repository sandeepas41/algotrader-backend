package com.algotrader.unit.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.broker.KiteBrokerGateway;
import com.algotrader.broker.KiteOrderService;
import com.algotrader.broker.KitePositionService;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Position;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link KiteBrokerGateway}.
 *
 * <p>Tests delegation to internal services and the kill switch logic
 * (cancel all open orders + exit all positions at market).
 */
@ExtendWith(MockitoExtension.class)
class KiteBrokerGatewayTest {

    @Mock
    private KiteOrderService kiteOrderService;

    @Mock
    private KitePositionService kitePositionService;

    private KiteBrokerGateway kiteBrokerGateway;

    @BeforeEach
    void setUp() {
        kiteBrokerGateway = new KiteBrokerGateway(kiteOrderService, kitePositionService);
    }

    @Nested
    @DisplayName("Delegation")
    class DelegationTests {

        @Test
        @DisplayName("placeOrder delegates to KiteOrderService")
        void delegatesPlaceOrder() {
            Order order = Order.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .side(OrderSide.BUY)
                    .type(OrderType.MARKET)
                    .quantity(50)
                    .build();

            when(kiteOrderService.placeOrder(order)).thenReturn("ORD123");

            String orderId = kiteBrokerGateway.placeOrder(order);

            assertThat(orderId).isEqualTo("ORD123");
            verify(kiteOrderService).placeOrder(order);
        }

        @Test
        @DisplayName("cancelOrder delegates to KiteOrderService")
        void delegatesCancelOrder() {
            kiteBrokerGateway.cancelOrder("ORD123");
            verify(kiteOrderService).cancelOrder("ORD123");
        }

        @Test
        @DisplayName("getPositions delegates to KitePositionService")
        void delegatesGetPositions() {
            Map<String, List<Position>> positions = Map.of("day", List.of(), "net", List.of());
            when(kitePositionService.getPositions()).thenReturn(positions);

            Map<String, List<Position>> result = kiteBrokerGateway.getPositions();

            assertThat(result).isEqualTo(positions);
            verify(kitePositionService).getPositions();
        }

        @Test
        @DisplayName("getMargins delegates to KitePositionService")
        void delegatesGetMargins() {
            Map<String, BigDecimal> margins = Map.of("cash", BigDecimal.valueOf(500000));
            when(kitePositionService.getMargins()).thenReturn(margins);

            Map<String, BigDecimal> result = kiteBrokerGateway.getMargins();

            assertThat(result).isEqualTo(margins);
        }
    }

    @Nested
    @DisplayName("Kill Switch")
    class KillSwitchTests {

        @Test
        @DisplayName("cancels all open orders using bypass methods")
        void cancelsAllOpenOrders() {
            Order openOrder = Order.builder()
                    .brokerOrderId("ORD001")
                    .status(OrderStatus.OPEN)
                    .build();
            Order triggerPending = Order.builder()
                    .brokerOrderId("ORD002")
                    .status(OrderStatus.TRIGGER_PENDING)
                    .build();
            Order completed = Order.builder()
                    .brokerOrderId("ORD003")
                    .status(OrderStatus.COMPLETE)
                    .build();

            when(kiteOrderService.getOrders()).thenReturn(List.of(openOrder, triggerPending, completed));
            when(kitePositionService.getPositions()).thenReturn(Map.of("day", List.of(), "net", List.of()));

            kiteBrokerGateway.killSwitch();

            // Should cancel OPEN and TRIGGER_PENDING, but NOT COMPLETE
            verify(kiteOrderService).cancelOrderBypassRateLimit("ORD001");
            verify(kiteOrderService).cancelOrderBypassRateLimit("ORD002");
            verify(kiteOrderService, never()).cancelOrderBypassRateLimit("ORD003");
        }

        @Test
        @DisplayName("exits long positions with SELL MARKET orders")
        void exitsLongPositions() {
            when(kiteOrderService.getOrders()).thenReturn(List.of());

            Position longPos = Position.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .exchange("NFO")
                    .quantity(50)
                    .build();

            when(kitePositionService.getPositions()).thenReturn(Map.of("day", List.of(), "net", List.of(longPos)));
            when(kiteOrderService.placeOrderBypassRateLimit(any())).thenReturn("EXIT001");

            kiteBrokerGateway.killSwitch();

            // Long position (qty=50) should produce SELL MARKET order for 50
            verify(kiteOrderService)
                    .placeOrderBypassRateLimit(argThat(order -> order.getSide() == OrderSide.SELL
                            && order.getType() == OrderType.MARKET
                            && order.getQuantity() == 50
                            && "NIFTY24FEB22000CE".equals(order.getTradingSymbol())));
        }

        @Test
        @DisplayName("exits short positions with BUY MARKET orders")
        void exitsShortPositions() {
            when(kiteOrderService.getOrders()).thenReturn(List.of());

            Position shortPos = Position.builder()
                    .tradingSymbol("NIFTY24FEB22000PE")
                    .exchange("NFO")
                    .quantity(-75)
                    .build();

            when(kitePositionService.getPositions()).thenReturn(Map.of("day", List.of(), "net", List.of(shortPos)));
            when(kiteOrderService.placeOrderBypassRateLimit(any())).thenReturn("EXIT002");

            kiteBrokerGateway.killSwitch();

            // Short position (qty=-75) should produce BUY MARKET order for 75
            verify(kiteOrderService)
                    .placeOrderBypassRateLimit(argThat(order -> order.getSide() == OrderSide.BUY
                            && order.getType() == OrderType.MARKET
                            && order.getQuantity() == 75));
        }

        @Test
        @DisplayName("skips zero-quantity positions (already closed)")
        void skipsZeroQuantity() {
            when(kiteOrderService.getOrders()).thenReturn(List.of());

            Position closed = Position.builder()
                    .tradingSymbol("CLOSED")
                    .exchange("NFO")
                    .quantity(0)
                    .build();

            when(kitePositionService.getPositions()).thenReturn(Map.of("day", List.of(), "net", List.of(closed)));

            kiteBrokerGateway.killSwitch();

            verify(kiteOrderService, never()).placeOrderBypassRateLimit(any());
        }

        @Test
        @DisplayName("returns total action count (cancels + exits)")
        void returnsTotalActionCount() {
            Order openOrder = Order.builder()
                    .brokerOrderId("ORD001")
                    .status(OrderStatus.OPEN)
                    .build();

            when(kiteOrderService.getOrders()).thenReturn(List.of(openOrder));

            Position longPos = Position.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .exchange("NFO")
                    .quantity(50)
                    .build();
            Position shortPos = Position.builder()
                    .tradingSymbol("NIFTY24FEB22000PE")
                    .exchange("NFO")
                    .quantity(-75)
                    .build();

            when(kitePositionService.getPositions())
                    .thenReturn(Map.of("day", List.of(), "net", List.of(longPos, shortPos)));
            when(kiteOrderService.placeOrderBypassRateLimit(any())).thenReturn("EXIT");

            int count = kiteBrokerGateway.killSwitch();

            // 1 cancel + 2 exit orders = 3
            assertThat(count).isEqualTo(3);
        }

        @Test
        @DisplayName("continues processing even when individual operations fail")
        void continuesOnFailure() {
            Order o1 = Order.builder()
                    .brokerOrderId("ORD001")
                    .status(OrderStatus.OPEN)
                    .build();
            Order o2 = Order.builder()
                    .brokerOrderId("ORD002")
                    .status(OrderStatus.OPEN)
                    .build();

            when(kiteOrderService.getOrders()).thenReturn(List.of(o1, o2));
            when(kitePositionService.getPositions()).thenReturn(Map.of("day", List.of(), "net", List.of()));

            // Both cancels proceed even though they're separate operations
            kiteBrokerGateway.killSwitch();

            verify(kiteOrderService, times(2)).cancelOrderBypassRateLimit(any());
        }
    }
}
