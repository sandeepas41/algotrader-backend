package com.algotrader.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.OrderController;
import com.algotrader.broker.BrokerGateway;
import com.algotrader.config.ApiResponseAdvice;
import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.algotrader.oms.OrderRequest;
import com.algotrader.oms.OrderRouteResult;
import com.algotrader.oms.OrderRouter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc tests for the OrderController.
 * Uses MockMvcBuilders.standaloneSetup since @WebMvcTest was removed in Spring Boot 4.0.
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    private MockMvc mockMvc;

    @Mock
    private OrderRouter orderRouter;

    @Mock
    private BrokerGateway brokerGateway;

    @BeforeEach
    void setUp() {
        OrderController controller = new OrderController(orderRouter, brokerGateway);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiResponseAdvice())
                .build();
    }

    @Test
    @DisplayName("GET /api/orders returns all orders from broker")
    void listOrdersReturnsBrokerOrders() throws Exception {
        Order order1 = Order.builder()
                .brokerOrderId("ORD-001")
                .tradingSymbol("NIFTY25FEB24500CE")
                .side(OrderSide.BUY)
                .type(OrderType.LIMIT)
                .quantity(50)
                .price(BigDecimal.valueOf(120.50))
                .status(OrderStatus.COMPLETE)
                .filledQuantity(50)
                .build();
        Order order2 = Order.builder()
                .brokerOrderId("ORD-002")
                .tradingSymbol("NIFTY25FEB24500PE")
                .side(OrderSide.SELL)
                .type(OrderType.MARKET)
                .quantity(50)
                .status(OrderStatus.OPEN)
                .build();

        when(brokerGateway.getOrders()).thenReturn(List.of(order1, order2));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].brokerOrderId").value("ORD-001"))
                .andExpect(jsonPath("$.data[0].tradingSymbol").value("NIFTY25FEB24500CE"))
                .andExpect(jsonPath("$.data[1].brokerOrderId").value("ORD-002"));
    }

    @Test
    @DisplayName("GET /api/orders/{brokerOrderId}/history returns order transitions")
    void getOrderHistoryReturnsTransitions() throws Exception {
        Order open = Order.builder()
                .brokerOrderId("ORD-001")
                .status(OrderStatus.OPEN)
                .updatedAt(LocalDateTime.of(2025, 2, 7, 9, 15, 0))
                .build();
        Order complete = Order.builder()
                .brokerOrderId("ORD-001")
                .status(OrderStatus.COMPLETE)
                .filledQuantity(50)
                .updatedAt(LocalDateTime.of(2025, 2, 7, 9, 15, 30))
                .build();

        when(brokerGateway.getOrderHistory("ORD-001")).thenReturn(List.of(open, complete));

        mockMvc.perform(get("/api/orders/ORD-001/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data[1].status").value("COMPLETE"));
    }

    @Test
    @DisplayName("POST /api/orders places order through OMS and returns ACCEPTED")
    void placeOrderReturnsAccepted() throws Exception {
        when(orderRouter.route(any(OrderRequest.class), eq(OrderPriority.MANUAL)))
                .thenReturn(OrderRouteResult.accepted("ORD-123"));

        String body = """
                {
                    "instrumentToken": 12345,
                    "tradingSymbol": "NIFTY25FEB24500CE",
                    "exchange": "NFO",
                    "side": "BUY",
                    "type": "LIMIT",
                    "product": "NRML",
                    "quantity": 50,
                    "price": "120.50"
                }
                """;

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.orderId").value("ORD-123"));
    }

    @Test
    @DisplayName("POST /api/orders returns REJECTED when kill switch is active")
    void placeOrderReturnsRejectedWhenKillSwitchActive() throws Exception {
        when(orderRouter.route(any(OrderRequest.class), eq(OrderPriority.MANUAL)))
                .thenReturn(OrderRouteResult.rejected("Kill switch is active"));

        String body = """
                {
                    "instrumentToken": 12345,
                    "tradingSymbol": "NIFTY25FEB24500CE",
                    "side": "BUY",
                    "type": "MARKET",
                    "quantity": 50
                }
                """;

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.message").value("Kill switch is active"));
    }

    @Test
    @DisplayName("PUT /api/orders/{brokerOrderId} modifies an open order")
    void modifyOrderCallsBrokerGateway() throws Exception {
        String body = """
                {
                    "price": "125.00",
                    "quantity": 100
                }
                """;

        mockMvc.perform(put("/api/orders/ORD-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Order modified successfully"))
                .andExpect(jsonPath("$.data.brokerOrderId").value("ORD-001"));

        verify(brokerGateway).modifyOrder(eq("ORD-001"), any(Order.class));
    }

    @Test
    @DisplayName("DELETE /api/orders/{brokerOrderId} cancels an open order")
    void cancelOrderCallsBrokerGateway() throws Exception {
        mockMvc.perform(delete("/api/orders/ORD-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Order cancelled successfully"))
                .andExpect(jsonPath("$.data.brokerOrderId").value("ORD-001"));

        verify(brokerGateway).cancelOrder("ORD-001");
    }
}
