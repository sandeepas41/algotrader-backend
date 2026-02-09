package com.algotrader.api.controller;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.algotrader.oms.OrderRequest;
import com.algotrader.oms.OrderRouteResult;
import com.algotrader.oms.OrderRouter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for order management: listing, placement, modification, and cancellation.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/orders -- list all today's orders from broker</li>
 *   <li>GET /api/orders/{brokerOrderId}/history -- order state transitions</li>
 *   <li>POST /api/orders -- place a new order through the OMS</li>
 *   <li>PUT /api/orders/{brokerOrderId} -- modify an open order</li>
 *   <li>DELETE /api/orders/{brokerOrderId} -- cancel an open order</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderRouter orderRouter;
    private final BrokerGateway brokerGateway;

    public OrderController(OrderRouter orderRouter, BrokerGateway brokerGateway) {
        this.orderRouter = orderRouter;
        this.brokerGateway = brokerGateway;
    }

    /**
     * Returns all today's orders from the broker.
     */
    @GetMapping
    public ResponseEntity<List<Order>> listOrders() {
        List<Order> orders = brokerGateway.getOrders();
        return ResponseEntity.ok(orders);
    }

    /**
     * Returns the order history (state transitions) for a specific order.
     */
    @GetMapping("/{brokerOrderId}/history")
    public ResponseEntity<List<Order>> getOrderHistory(@PathVariable String brokerOrderId) {
        List<Order> history = brokerGateway.getOrderHistory(brokerOrderId);
        return ResponseEntity.ok(history);
    }

    /**
     * Places a new order through the OMS routing pipeline.
     * The order goes through kill switch check, idempotency, and risk validation.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> placeOrder(@RequestBody Map<String, Object> body) {
        OrderRequest orderRequest = buildOrderRequest(body);
        log.info(
                "Manual order placement: {} {} {} x{}",
                orderRequest.getSide(),
                orderRequest.getType(),
                orderRequest.getTradingSymbol(),
                orderRequest.getQuantity());

        OrderRouteResult result = orderRouter.route(orderRequest, OrderPriority.MANUAL);

        if (result.isAccepted()) {
            return ResponseEntity.ok(Map.of(
                    "status", "ACCEPTED",
                    "orderId", result.getOrderId() != null ? result.getOrderId() : "",
                    "message", "Order submitted to routing pipeline"));
        } else {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "status",
                            "REJECTED",
                            "message",
                            result.getRejectionReason() != null ? result.getRejectionReason() : "Order rejected"));
        }
    }

    /**
     * Modifies an open order (price, trigger price, or quantity).
     */
    @PutMapping("/{brokerOrderId}")
    public ResponseEntity<Map<String, String>> modifyOrder(
            @PathVariable String brokerOrderId, @RequestBody Map<String, Object> body) {
        Order modification = Order.builder()
                .price(
                        body.containsKey("price")
                                ? new BigDecimal(body.get("price").toString())
                                : null)
                .triggerPrice(
                        body.containsKey("triggerPrice")
                                ? new BigDecimal(body.get("triggerPrice").toString())
                                : null)
                .quantity(body.containsKey("quantity") ? ((Number) body.get("quantity")).intValue() : 0)
                .build();

        log.info(
                "Modifying order {}: price={}, qty={}",
                brokerOrderId,
                modification.getPrice(),
                modification.getQuantity());
        brokerGateway.modifyOrder(brokerOrderId, modification);

        return ResponseEntity.ok(Map.of("message", "Order modified successfully", "brokerOrderId", brokerOrderId));
    }

    /**
     * Cancels an open order.
     */
    @DeleteMapping("/{brokerOrderId}")
    public ResponseEntity<Map<String, String>> cancelOrder(@PathVariable String brokerOrderId) {
        log.info("Cancelling order {}", brokerOrderId);
        brokerGateway.cancelOrder(brokerOrderId);
        return ResponseEntity.ok(Map.of("message", "Order cancelled successfully", "brokerOrderId", brokerOrderId));
    }

    private OrderRequest buildOrderRequest(Map<String, Object> body) {
        return OrderRequest.builder()
                .instrumentToken(((Number) body.get("instrumentToken")).longValue())
                .tradingSymbol((String) body.get("tradingSymbol"))
                .exchange((String) body.getOrDefault("exchange", "NFO"))
                .side(OrderSide.valueOf((String) body.get("side")))
                .type(OrderType.valueOf((String) body.get("type")))
                .product((String) body.getOrDefault("product", "NRML"))
                .quantity(((Number) body.get("quantity")).intValue())
                .price(
                        body.containsKey("price")
                                ? new BigDecimal(body.get("price").toString())
                                : null)
                .triggerPrice(
                        body.containsKey("triggerPrice")
                                ? new BigDecimal(body.get("triggerPrice").toString())
                                : null)
                .strategyId((String) body.get("strategyId"))
                .correlationId((String) body.get("correlationId"))
                .build();
    }
}
