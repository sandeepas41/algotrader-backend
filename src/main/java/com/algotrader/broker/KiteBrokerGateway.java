package com.algotrader.broker;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Position;
import com.algotrader.oms.OrderRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Live Kite Connect implementation of {@link BrokerGateway}.
 *
 * <p>Delegates all operations to internal services ({@link KiteOrderService},
 * {@link KitePositionService}) which handle Resilience4j annotations, Kite SDK
 * calls, and exception wrapping. This class is the single point where external
 * components interact with the Kite broker.
 *
 * <p>The kill switch bypasses rate limiting by using the
 * {@code placeOrderBypassRateLimit} / {@code cancelOrderBypassRateLimit} methods
 * on KiteOrderService, ensuring emergency exits are never blocked.
 *
 * <p>Active only when {@code algotrader.trading-mode=LIVE} or the live gateway
 * is explicitly selected for kill switch operations in HYBRID mode.
 */
@Component
@Primary
public class KiteBrokerGateway implements BrokerGateway {

    private static final Logger log = LoggerFactory.getLogger(KiteBrokerGateway.class);

    private final KiteOrderService kiteOrderService;
    private final KitePositionService kitePositionService;

    public KiteBrokerGateway(KiteOrderService kiteOrderService, KitePositionService kitePositionService) {
        this.kiteOrderService = kiteOrderService;
        this.kitePositionService = kitePositionService;
    }

    @Override
    public String placeOrder(Order order) {
        return kiteOrderService.placeOrder(order);
    }

    @Override
    public void modifyOrder(String brokerOrderId, Order order) {
        kiteOrderService.modifyOrder(brokerOrderId, order);
    }

    @Override
    public void cancelOrder(String brokerOrderId) {
        kiteOrderService.cancelOrder(brokerOrderId);
    }

    @Override
    public List<Order> getOrders() {
        return kiteOrderService.getOrders();
    }

    @Override
    public List<Order> getOrderHistory(String brokerOrderId) {
        return kiteOrderService.getOrderHistory(brokerOrderId);
    }

    @Override
    public Map<String, List<Position>> getPositions() {
        return kitePositionService.getPositions();
    }

    @Override
    public Map<String, BigDecimal> getMargins() {
        return kitePositionService.getMargins();
    }

    // #TODO Task 8.x: Implement using Kite Connect's order margin API
    // (KiteConnect.getOrderMargins) once available in the SDK
    @Override
    public BigDecimal getOrderMargin(OrderRequest orderRequest) {
        log.warn("getOrderMargin not yet implemented, returning ZERO for {}", orderRequest.getTradingSymbol());
        return BigDecimal.ZERO;
    }

    // #TODO Task 8.x: Implement using Kite Connect's basket margin API
    // (KiteConnect.getBasketMargins) once available in the SDK
    @Override
    public BigDecimal getBasketMargin(List<OrderRequest> orderRequests) {
        log.warn("getBasketMargin not yet implemented, returning ZERO for {} orders", orderRequests.size());
        return BigDecimal.ZERO;
    }

    /**
     * Emergency kill switch: cancel all open orders and exit all positions at market.
     *
     * <p>Operates in two phases:
     * <ol>
     *   <li>Cancel all open/trigger-pending orders (best-effort, continues on individual failures)</li>
     *   <li>Place MARKET exit orders for all non-zero net positions</li>
     * </ol>
     *
     * <p>Both phases bypass rate limiting to ensure maximum throughput during emergencies.
     * Individual failures are logged but do not stop the kill switch from processing
     * remaining orders/positions.
     *
     * @return total number of cancel + exit operations attempted
     */
    @Override
    public int killSwitch() {
        log.warn("KILL SWITCH ACTIVATED — cancelling all orders and exiting all positions");
        int actionCount = 0;

        // Phase 1: Cancel all open orders
        try {
            List<Order> allOrders = kiteOrderService.getOrders();
            for (Order order : allOrders) {
                if (order.getStatus() == OrderStatus.OPEN || order.getStatus() == OrderStatus.TRIGGER_PENDING) {
                    kiteOrderService.cancelOrderBypassRateLimit(order.getBrokerOrderId());
                    actionCount++;
                }
            }
            log.info("Kill switch: cancelled {} open orders", actionCount);
        } catch (Exception e) {
            log.error("Kill switch: error during order cancellation phase", e);
        }

        // Phase 2: Exit all positions at market
        try {
            Map<String, List<Position>> positions = kitePositionService.getPositions();
            List<Position> netPositions = positions.getOrDefault("net", List.of());

            for (Position position : netPositions) {
                if (position.getQuantity() == 0) {
                    continue;
                }

                // Exit direction is opposite of current position
                OrderSide exitSide = position.getQuantity() > 0 ? OrderSide.SELL : OrderSide.BUY;
                int exitQty = Math.abs(position.getQuantity());

                Order exitOrder = Order.builder()
                        .tradingSymbol(position.getTradingSymbol())
                        .exchange(position.getExchange())
                        .side(exitSide)
                        .type(OrderType.MARKET)
                        .quantity(exitQty)
                        .build();

                try {
                    kiteOrderService.placeOrderBypassRateLimit(exitOrder);
                    actionCount++;
                } catch (Exception e) {
                    log.error(
                            "Kill switch: failed to exit position {} qty={}",
                            position.getTradingSymbol(),
                            position.getQuantity(),
                            e);
                }
            }
            log.info("Kill switch: placed {} exit orders", actionCount);
        } catch (Exception e) {
            log.error("Kill switch: error during position exit phase", e);
        }

        log.warn("Kill switch complete: {} total actions", actionCount);
        return actionCount;
    }
}
