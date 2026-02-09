package com.algotrader.api.websocket;

import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.oms.OrderRequest;
import com.algotrader.oms.OrderRouteResult;
import com.algotrader.oms.OrderRouter;
import java.math.BigDecimal;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

/**
 * Handles low-latency order placement via STOMP WebSocket.
 *
 * <p>The frontend can submit orders via the {@code /app/orders/place} STOMP destination
 * instead of the REST endpoint for lower latency. This is particularly useful for
 * scalping strategies where milliseconds matter.
 *
 * <p>The handler validates the incoming message, constructs an {@link OrderRequest},
 * and routes it through the standard OMS pipeline (idempotency -> kill switch ->
 * risk -> queue). The response is sent back to the submitting user's private queue
 * via {@code @SendToUser}.
 *
 * <p>Message format expected from frontend:
 * <pre>{@code
 * {
 *   "instrumentToken": 256265,
 *   "tradingSymbol": "NIFTY24FEB22000CE",
 *   "exchange": "NFO",
 *   "side": "BUY",
 *   "type": "MARKET",
 *   "quantity": 50,
 *   "price": null,
 *   "triggerPrice": null,
 *   "product": "NRML"
 * }
 * }</pre>
 */
@Controller
public class OrderSubmissionHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderSubmissionHandler.class);

    private final OrderRouter orderRouter;

    public OrderSubmissionHandler(OrderRouter orderRouter) {
        this.orderRouter = orderRouter;
    }

    /**
     * Receives an order placement request via STOMP WebSocket.
     *
     * <p>The incoming payload is a Map because STOMP message conversion is simpler
     * with flexible types. We manually construct the OrderRequest to validate fields.
     *
     * @param payload the order parameters from the frontend
     * @param headerAccessor STOMP session headers (for user identification)
     * @return a response map sent to the user's private queue
     */
    @MessageMapping("/orders/place")
    @SendToUser("/queue/order-result")
    public WebSocketMessage placeOrder(Map<String, Object> payload, SimpMessageHeaderAccessor headerAccessor) {
        try {
            OrderRequest orderRequest = buildOrderRequest(payload);

            log.info(
                    "WS order submission: {} {} {} x{} @ {}",
                    orderRequest.getSide(),
                    orderRequest.getType(),
                    orderRequest.getTradingSymbol(),
                    orderRequest.getQuantity(),
                    orderRequest.getPrice());

            // WebSocket orders from the UI are manual priority
            OrderRouteResult routeResult = orderRouter.route(orderRequest, OrderPriority.MANUAL);

            if (routeResult.isAccepted()) {
                Map<String, Object> result = Map.of(
                        "status",
                        "ACCEPTED",
                        "tradingSymbol",
                        orderRequest.getTradingSymbol(),
                        "orderId",
                        routeResult.getOrderId() != null ? routeResult.getOrderId() : "",
                        "message",
                        "Order submitted to routing pipeline");
                return WebSocketMessage.of("ORDER_RESULT", result);
            } else {
                return WebSocketMessage.of(
                        "ORDER_RESULT",
                        Map.of(
                                "status",
                                "REJECTED",
                                "message",
                                routeResult.getRejectionReason() != null
                                        ? routeResult.getRejectionReason()
                                        : "Order rejected by routing pipeline"));
            }

        } catch (IllegalArgumentException e) {
            log.warn("WS order validation failed: {}", e.getMessage());
            return WebSocketMessage.of("ORDER_RESULT", Map.of("status", "REJECTED", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("WS order submission failed: {}", e.getMessage(), e);
            return WebSocketMessage.of("ORDER_RESULT", Map.of("status", "ERROR", "message", "Order submission failed"));
        }
    }

    private OrderRequest buildOrderRequest(Map<String, Object> payload) {
        // Validate required fields
        requireField(payload, "instrumentToken");
        requireField(payload, "tradingSymbol");
        requireField(payload, "exchange");
        requireField(payload, "side");
        requireField(payload, "type");
        requireField(payload, "quantity");

        long instrumentToken = ((Number) payload.get("instrumentToken")).longValue();
        String tradingSymbol = (String) payload.get("tradingSymbol");
        String exchange = (String) payload.get("exchange");
        OrderSide side = OrderSide.valueOf((String) payload.get("side"));
        OrderType type = OrderType.valueOf((String) payload.get("type"));
        int quantity = ((Number) payload.get("quantity")).intValue();
        String product = payload.containsKey("product") ? (String) payload.get("product") : "NRML";

        BigDecimal price = payload.get("price") != null
                ? new BigDecimal(payload.get("price").toString())
                : null;

        BigDecimal triggerPrice = payload.get("triggerPrice") != null
                ? new BigDecimal(payload.get("triggerPrice").toString())
                : null;

        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        return OrderRequest.builder()
                .instrumentToken(instrumentToken)
                .tradingSymbol(tradingSymbol)
                .exchange(exchange)
                .side(side)
                .type(type)
                .quantity(quantity)
                .price(price)
                .triggerPrice(triggerPrice)
                .product(product)
                .build();
    }

    private void requireField(Map<String, Object> payload, String fieldName) {
        if (!payload.containsKey(fieldName) || payload.get(fieldName) == null) {
            throw new IllegalArgumentException("Missing required field: " + fieldName);
        }
    }
}
