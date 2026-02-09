package com.algotrader.unit.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.api.websocket.OrderSubmissionHandler;
import com.algotrader.api.websocket.WebSocketMessage;
import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.oms.OrderRequest;
import com.algotrader.oms.OrderRouteResult;
import com.algotrader.oms.OrderRouter;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests that the OrderSubmissionHandler correctly builds OrderRequest from
 * STOMP message payloads, routes through OrderRouter with MANUAL priority,
 * and returns appropriate response messages.
 */
@ExtendWith(MockitoExtension.class)
class OrderSubmissionHandlerTest {

    @Mock
    private OrderRouter orderRouter;

    private OrderSubmissionHandler orderSubmissionHandler;

    @BeforeEach
    void setUp() {
        orderSubmissionHandler = new OrderSubmissionHandler(orderRouter);
    }

    private Map<String, Object> validPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("instrumentToken", 256265L);
        payload.put("tradingSymbol", "NIFTY24FEB22000CE");
        payload.put("exchange", "NFO");
        payload.put("side", "BUY");
        payload.put("type", "MARKET");
        payload.put("quantity", 50);
        payload.put("product", "NRML");
        payload.put("price", null);
        payload.put("triggerPrice", null);
        return payload;
    }

    @Nested
    @DisplayName("Successful order submission")
    class SuccessfulSubmission {

        @Test
        @DisplayName("routes order through OrderRouter with MANUAL priority")
        void routesOrderWithManualPriority() {
            when(orderRouter.route(any(OrderRequest.class), eq(OrderPriority.MANUAL)))
                    .thenReturn(OrderRouteResult.accepted("KT-12345"));

            orderSubmissionHandler.placeOrder(validPayload(), null);

            ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
            verify(orderRouter).route(captor.capture(), eq(OrderPriority.MANUAL));

            OrderRequest request = captor.getValue();
            assertThat(request.getInstrumentToken()).isEqualTo(256265L);
            assertThat(request.getTradingSymbol()).isEqualTo("NIFTY24FEB22000CE");
            assertThat(request.getExchange()).isEqualTo("NFO");
            assertThat(request.getSide()).isEqualTo(OrderSide.BUY);
            assertThat(request.getType()).isEqualTo(OrderType.MARKET);
            assertThat(request.getQuantity()).isEqualTo(50);
            assertThat(request.getProduct()).isEqualTo("NRML");
        }

        @Test
        @DisplayName("returns ACCEPTED status when order is accepted")
        @SuppressWarnings("unchecked")
        void returnsAcceptedStatus() {
            when(orderRouter.route(any(OrderRequest.class), eq(OrderPriority.MANUAL)))
                    .thenReturn(OrderRouteResult.accepted("KT-12345"));

            WebSocketMessage result = orderSubmissionHandler.placeOrder(validPayload(), null);

            assertThat(result.getType()).isEqualTo("ORDER_RESULT");
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertThat(data).containsEntry("status", "ACCEPTED");
            assertThat(data).containsEntry("tradingSymbol", "NIFTY24FEB22000CE");
            assertThat(data).containsEntry("orderId", "KT-12345");
        }

        @Test
        @DisplayName("returns REJECTED status when order is rejected by router")
        @SuppressWarnings("unchecked")
        void returnsRejectedWhenRouterRejects() {
            when(orderRouter.route(any(OrderRequest.class), eq(OrderPriority.MANUAL)))
                    .thenReturn(OrderRouteResult.rejected("Kill switch is active"));

            WebSocketMessage result = orderSubmissionHandler.placeOrder(validPayload(), null);

            assertThat(result.getType()).isEqualTo("ORDER_RESULT");
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertThat(data).containsEntry("status", "REJECTED");
            assertThat(data).containsEntry("message", "Kill switch is active");
        }

        @Test
        @DisplayName("handles LIMIT order with price")
        void handlesLimitOrderWithPrice() {
            Map<String, Object> payload = validPayload();
            payload.put("type", "LIMIT");
            payload.put("price", "155.50");

            when(orderRouter.route(any(OrderRequest.class), eq(OrderPriority.MANUAL)))
                    .thenReturn(OrderRouteResult.accepted("KT-12346"));

            orderSubmissionHandler.placeOrder(payload, null);

            ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
            verify(orderRouter).route(captor.capture(), eq(OrderPriority.MANUAL));

            assertThat(captor.getValue().getType()).isEqualTo(OrderType.LIMIT);
            assertThat(captor.getValue().getPrice()).isEqualByComparingTo(new BigDecimal("155.50"));
        }

        @Test
        @DisplayName("defaults product to NRML when not provided")
        void defaultsProductToNrml() {
            Map<String, Object> payload = validPayload();
            payload.remove("product");

            when(orderRouter.route(any(OrderRequest.class), eq(OrderPriority.MANUAL)))
                    .thenReturn(OrderRouteResult.accepted("KT-12347"));

            orderSubmissionHandler.placeOrder(payload, null);

            ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
            verify(orderRouter).route(captor.capture(), eq(OrderPriority.MANUAL));

            assertThat(captor.getValue().getProduct()).isEqualTo("NRML");
        }
    }

    @Nested
    @DisplayName("Validation errors")
    class ValidationErrors {

        @Test
        @DisplayName("rejects when instrumentToken is missing")
        @SuppressWarnings("unchecked")
        void rejectsWhenInstrumentTokenMissing() {
            Map<String, Object> payload = validPayload();
            payload.remove("instrumentToken");

            WebSocketMessage result = orderSubmissionHandler.placeOrder(payload, null);

            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertThat(data).containsEntry("status", "REJECTED");
            assertThat((String) data.get("message")).contains("instrumentToken");
        }

        @Test
        @DisplayName("rejects when tradingSymbol is missing")
        @SuppressWarnings("unchecked")
        void rejectsWhenTradingSymbolMissing() {
            Map<String, Object> payload = validPayload();
            payload.remove("tradingSymbol");

            WebSocketMessage result = orderSubmissionHandler.placeOrder(payload, null);

            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertThat(data).containsEntry("status", "REJECTED");
            assertThat((String) data.get("message")).contains("tradingSymbol");
        }

        @Test
        @DisplayName("rejects when quantity is zero or negative")
        @SuppressWarnings("unchecked")
        void rejectsWhenQuantityNotPositive() {
            Map<String, Object> payload = validPayload();
            payload.put("quantity", 0);

            WebSocketMessage result = orderSubmissionHandler.placeOrder(payload, null);

            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertThat(data).containsEntry("status", "REJECTED");
            assertThat((String) data.get("message")).contains("Quantity");
        }

        @Test
        @DisplayName("rejects when side is invalid enum value")
        @SuppressWarnings("unchecked")
        void rejectsWhenSideIsInvalid() {
            Map<String, Object> payload = validPayload();
            payload.put("side", "INVALID");

            WebSocketMessage result = orderSubmissionHandler.placeOrder(payload, null);

            // IllegalArgumentException from valueOf() is caught by the validation catch block
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertThat(data).containsEntry("status", "REJECTED");
            assertThat((String) data.get("message")).contains("INVALID");
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("returns ERROR when OrderRouter throws unexpected exception")
        @SuppressWarnings("unchecked")
        void returnsErrorOnUnexpectedException() {
            when(orderRouter.route(any(OrderRequest.class), any(OrderPriority.class)))
                    .thenThrow(new RuntimeException("Connection timeout"));

            WebSocketMessage result = orderSubmissionHandler.placeOrder(validPayload(), null);

            assertThat(result.getType()).isEqualTo("ORDER_RESULT");
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertThat(data).containsEntry("status", "ERROR");
            assertThat(data).containsEntry("message", "Order submission failed");
        }
    }
}
