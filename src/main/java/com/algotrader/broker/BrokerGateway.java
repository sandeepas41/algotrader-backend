package com.algotrader.broker;

import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Position;
import com.algotrader.oms.OrderRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Unified abstraction for all broker operations. Every component that needs to place
 * orders, fetch positions, or query margins MUST go through this interface — never
 * directly through broker-specific classes.
 *
 * <p>Two implementations exist:
 * <ul>
 *   <li>{@code KiteBrokerGateway} — real Kite Connect API for live trading</li>
 *   <li>{@code SimulatorBrokerGateway} — in-memory simulator for paper trading (#TODO Task 6.x)</li>
 * </ul>
 *
 * <p>The active implementation is selected based on the {@code algotrader.trading-mode}
 * property (LIVE vs PAPER vs HYBRID). In HYBRID mode, the kill switch and risk exits
 * always use the live gateway.
 */
public interface BrokerGateway {

    // ---- Orders ----

    /**
     * Places a new order with the broker.
     *
     * @param order the order to place (must have tradingSymbol, exchange, side, type, quantity)
     * @return the broker-assigned order ID
     * @throws com.algotrader.exception.BrokerException if the order is rejected or the broker is unavailable
     */
    String placeOrder(Order order);

    /**
     * Modifies an existing open order (price, trigger price, or quantity).
     *
     * @param brokerOrderId the broker-assigned order ID to modify
     * @param order         the updated order fields (only price, triggerPrice, quantity are used)
     * @throws com.algotrader.exception.BrokerException if modification fails
     */
    void modifyOrder(String brokerOrderId, Order order);

    /**
     * Cancels an open order.
     *
     * @param brokerOrderId the broker-assigned order ID to cancel
     * @throws com.algotrader.exception.BrokerException if cancellation fails
     */
    void cancelOrder(String brokerOrderId);

    /**
     * Retrieves all orders for the current trading day.
     *
     * @return list of all orders (open + completed + cancelled + rejected)
     */
    List<Order> getOrders();

    /**
     * Retrieves a specific order's history (all state transitions).
     *
     * @param brokerOrderId the broker-assigned order ID
     * @return order history entries sorted chronologically
     */
    List<Order> getOrderHistory(String brokerOrderId);

    // ---- Positions ----

    /**
     * Retrieves all current positions (day + net).
     *
     * @return map with "day" and "net" position lists
     */
    Map<String, List<Position>> getPositions();

    // ---- Margins ----

    /**
     * Retrieves available and utilised margins for the equity segment.
     *
     * @return margin data as a map (keys: "cash", "collateral", "used", "available", "net")
     */
    Map<String, BigDecimal> getMargins();

    /**
     * Estimates the margin required for a single order using the broker's margin API.
     *
     * @param orderRequest the proposed order
     * @return the required margin amount
     * @throws com.algotrader.exception.BrokerException if the margin API call fails
     */
    BigDecimal getOrderMargin(OrderRequest orderRequest);

    /**
     * Estimates the combined margin for a basket of orders, accounting for hedging benefits.
     *
     * @param orderRequests the list of proposed orders forming a multi-leg strategy
     * @return the combined margin requirement (less than sum of individuals due to hedging)
     * @throws com.algotrader.exception.BrokerException if the margin API call fails
     */
    BigDecimal getBasketMargin(List<OrderRequest> orderRequests);

    // ---- Kill Switch ----

    /**
     * Emergency order cancellation and position exit. Bypasses rate limiting.
     * Cancels all open orders and places market exit orders for all open positions.
     *
     * @return the number of cancel/exit orders placed
     */
    int killSwitch();
}
