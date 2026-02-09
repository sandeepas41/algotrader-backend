package com.algotrader.unit.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.broker.KiteOrderService;
import com.algotrader.broker.mapper.KiteOrderMapper;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.algotrader.exception.BrokerException;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.OrderParams;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link KiteOrderService}.
 *
 * <p>Tests order placement, modification, cancellation, and error handling.
 * KiteConnect and KiteOrderMapper are mocked. Resilience4j annotations are
 * NOT active in unit tests (no Spring context), so we test the core logic only.
 */
@ExtendWith(MockitoExtension.class)
class KiteOrderServiceTest {

    @Mock
    private KiteConnect kiteConnect;

    private KiteOrderMapper kiteOrderMapper;
    private KiteOrderService kiteOrderService;

    @BeforeEach
    void setUp() {
        // Use real mapper — it's a simple POJO converter with no dependencies
        kiteOrderMapper = new KiteOrderMapper();
        kiteOrderService = new KiteOrderService(kiteConnect, kiteOrderMapper);
    }

    @Nested
    @DisplayName("Place Order")
    class PlaceOrderTests {

        @Test
        @DisplayName("places a MARKET BUY order and returns order ID")
        void placesMarketBuyOrder() throws Throwable {
            Order order = Order.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .exchange("NFO")
                    .side(OrderSide.BUY)
                    .type(OrderType.MARKET)
                    .quantity(50)
                    .build();

            com.zerodhatech.models.Order kiteResponse = new com.zerodhatech.models.Order();
            kiteResponse.orderId = "220101000001234";

            when(kiteConnect.placeOrder(any(OrderParams.class), eq(Constants.VARIETY_REGULAR)))
                    .thenReturn(kiteResponse);

            String orderId = kiteOrderService.placeOrder(order);

            assertThat(orderId).isEqualTo("220101000001234");

            // Verify the OrderParams sent to Kite
            ArgumentCaptor<OrderParams> captor = ArgumentCaptor.forClass(OrderParams.class);
            verify(kiteConnect).placeOrder(captor.capture(), eq(Constants.VARIETY_REGULAR));

            OrderParams params = captor.getValue();
            assertThat(params.tradingsymbol).isEqualTo("NIFTY24FEB22000CE");
            assertThat(params.transactionType).isEqualTo("BUY");
            assertThat(params.orderType).isEqualTo("MARKET");
            assertThat(params.quantity).isEqualTo(50);
        }

        @Test
        @DisplayName("places a LIMIT SELL order with price")
        void placesLimitSellOrder() throws Throwable {
            Order order = Order.builder()
                    .tradingSymbol("NIFTY24FEB22000PE")
                    .exchange("NFO")
                    .side(OrderSide.SELL)
                    .type(OrderType.LIMIT)
                    .quantity(75)
                    .price(new BigDecimal("80.50"))
                    .build();

            com.zerodhatech.models.Order kiteResponse = new com.zerodhatech.models.Order();
            kiteResponse.orderId = "220101000005678";

            when(kiteConnect.placeOrder(any(OrderParams.class), eq(Constants.VARIETY_REGULAR)))
                    .thenReturn(kiteResponse);

            String orderId = kiteOrderService.placeOrder(order);
            assertThat(orderId).isEqualTo("220101000005678");

            ArgumentCaptor<OrderParams> captor = ArgumentCaptor.forClass(OrderParams.class);
            verify(kiteConnect).placeOrder(captor.capture(), eq(Constants.VARIETY_REGULAR));
            assertThat(captor.getValue().price).isEqualTo(80.50);
        }

        @Test
        @DisplayName("wraps KiteException as BrokerException")
        void wrapsKiteException() throws Throwable {
            Order order = Order.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .side(OrderSide.BUY)
                    .type(OrderType.MARKET)
                    .quantity(50)
                    .build();

            KiteException kiteEx = new KiteException("Insufficient margin", 400);
            when(kiteConnect.placeOrder(any(OrderParams.class), any())).thenThrow(kiteEx);

            assertThatThrownBy(() -> kiteOrderService.placeOrder(order))
                    .isInstanceOf(BrokerException.class)
                    .hasMessageContaining("Insufficient margin");
        }

        @Test
        @DisplayName("wraps IOException as BrokerException")
        void wrapsIOException() throws Throwable {
            Order order = Order.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .side(OrderSide.BUY)
                    .type(OrderType.MARKET)
                    .quantity(50)
                    .build();

            when(kiteConnect.placeOrder(any(OrderParams.class), any()))
                    .thenThrow(new IOException("Connection refused"));

            assertThatThrownBy(() -> kiteOrderService.placeOrder(order))
                    .isInstanceOf(BrokerException.class)
                    .hasMessageContaining("Connection refused");
        }
    }

    @Nested
    @DisplayName("Modify Order")
    class ModifyOrderTests {

        @Test
        @DisplayName("modifies order price")
        void modifiesOrderPrice() throws Throwable {
            Order order = Order.builder().price(new BigDecimal("155.00")).build();

            when(kiteConnect.modifyOrder(eq("12345"), any(OrderParams.class), eq(Constants.VARIETY_REGULAR)))
                    .thenReturn(new com.zerodhatech.models.Order());

            kiteOrderService.modifyOrder("12345", order);

            ArgumentCaptor<OrderParams> captor = ArgumentCaptor.forClass(OrderParams.class);
            verify(kiteConnect).modifyOrder(eq("12345"), captor.capture(), eq(Constants.VARIETY_REGULAR));
            assertThat(captor.getValue().price).isEqualTo(155.00);
        }

        @Test
        @DisplayName("wraps modification KiteException as BrokerException")
        void wrapsModifyError() throws Throwable {
            Order order = Order.builder().price(new BigDecimal("155.00")).build();

            KiteException kiteEx = new KiteException("Order not open", 400);
            when(kiteConnect.modifyOrder(any(), any(OrderParams.class), any())).thenThrow(kiteEx);

            assertThatThrownBy(() -> kiteOrderService.modifyOrder("12345", order))
                    .isInstanceOf(BrokerException.class)
                    .hasMessageContaining("Order not open");
        }
    }

    @Nested
    @DisplayName("Cancel Order")
    class CancelOrderTests {

        @Test
        @DisplayName("cancels an order by ID")
        void cancelsOrder() throws Throwable {
            when(kiteConnect.cancelOrder("12345", Constants.VARIETY_REGULAR))
                    .thenReturn(new com.zerodhatech.models.Order());

            kiteOrderService.cancelOrder("12345");

            verify(kiteConnect).cancelOrder("12345", Constants.VARIETY_REGULAR);
        }

        @Test
        @DisplayName("wraps cancel KiteException as BrokerException")
        void wrapsCancelError() throws Throwable {
            KiteException kiteEx = new KiteException("Order already cancelled", 400);
            when(kiteConnect.cancelOrder(any(), any())).thenThrow(kiteEx);

            assertThatThrownBy(() -> kiteOrderService.cancelOrder("12345")).isInstanceOf(BrokerException.class);
        }
    }

    @Nested
    @DisplayName("Get Orders")
    class GetOrdersTests {

        @Test
        @DisplayName("fetches and maps all orders")
        void fetchesOrders() throws Throwable {
            com.zerodhatech.models.Order o1 = new com.zerodhatech.models.Order();
            o1.orderId = "1";
            o1.transactionType = "BUY";
            o1.status = "COMPLETE";
            o1.quantity = "50";

            com.zerodhatech.models.Order o2 = new com.zerodhatech.models.Order();
            o2.orderId = "2";
            o2.transactionType = "SELL";
            o2.status = "OPEN";
            o2.quantity = "75";

            when(kiteConnect.getOrders()).thenReturn(List.of(o1, o2));

            List<Order> orders = kiteOrderService.getOrders();

            assertThat(orders).hasSize(2);
            assertThat(orders.get(0).getBrokerOrderId()).isEqualTo("1");
            assertThat(orders.get(1).getBrokerOrderId()).isEqualTo("2");
        }

        @Test
        @DisplayName("wraps getOrders KiteException as BrokerException")
        void wrapsGetOrdersError() throws Throwable {
            when(kiteConnect.getOrders()).thenThrow(new KiteException("Token expired", 403));

            assertThatThrownBy(() -> kiteOrderService.getOrders()).isInstanceOf(BrokerException.class);
        }
    }

    @Nested
    @DisplayName("Kill Switch Bypass")
    class KillSwitchBypassTests {

        @Test
        @DisplayName("placeOrderBypassRateLimit places order without rate limiter")
        void bypassPlaceOrder() throws Throwable {
            Order order = Order.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .exchange("NFO")
                    .side(OrderSide.SELL)
                    .type(OrderType.MARKET)
                    .quantity(50)
                    .build();

            com.zerodhatech.models.Order kiteResponse = new com.zerodhatech.models.Order();
            kiteResponse.orderId = "KILL001";
            when(kiteConnect.placeOrder(any(OrderParams.class), eq(Constants.VARIETY_REGULAR)))
                    .thenReturn(kiteResponse);

            String orderId = kiteOrderService.placeOrderBypassRateLimit(order);
            assertThat(orderId).isEqualTo("KILL001");
        }

        @Test
        @DisplayName("cancelOrderBypassRateLimit does not throw on KiteException (best-effort)")
        void bypassCancelSwallowsError() throws Throwable {
            KiteException kiteEx = new KiteException("Already cancelled", 400);
            when(kiteConnect.cancelOrder(any(), any())).thenThrow(kiteEx);

            // Should NOT throw — kill switch cancel is best-effort
            kiteOrderService.cancelOrderBypassRateLimit("12345");
        }
    }
}
