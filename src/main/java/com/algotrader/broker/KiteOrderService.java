package com.algotrader.broker;

import com.algotrader.broker.mapper.KiteOrderMapper;
import com.algotrader.domain.model.Order;
import com.algotrader.exception.BrokerException;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.OrderParams;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.io.IOException;
import java.util.List;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Internal service that executes order operations against the Kite Connect API.
 *
 * <p>This is an implementation detail of {@link KiteBrokerGateway} — no other component
 * should inject this service directly. All external order operations must go through
 * {@link BrokerGateway} to ensure simulator compatibility.
 *
 * <p>Each method is annotated with Resilience4j decorators:
 * <ul>
 *   <li><b>Rate limiter</b> ({@code kiteOrders}): 8 req/sec — conservative vs Kite's 10/sec limit
 *       to leave headroom for kill switch bypass operations</li>
 *   <li><b>Circuit breaker</b> ({@code kiteApi}): trips after 50% failure rate in a 10-call window,
 *       30s wait in open state</li>
 *   <li><b>Retry</b> ({@code kiteApi}): 3 attempts with exponential backoff for transient errors
 *       (IOException, NetworkException). TokenException and InputException are NOT retried.</li>
 * </ul>
 *
 * <p>All methods wrap Kite's checked exceptions (KiteException, JSONException, IOException)
 * into our unchecked {@link BrokerException}.
 */
@Service
public class KiteOrderService {

    private static final Logger log = LoggerFactory.getLogger(KiteOrderService.class);

    private final KiteConnect kiteConnect;
    private final KiteOrderMapper kiteOrderMapper;

    public KiteOrderService(KiteConnect kiteConnect, KiteOrderMapper kiteOrderMapper) {
        this.kiteConnect = kiteConnect;
        this.kiteOrderMapper = kiteOrderMapper;
    }

    /**
     * Places an order via Kite API using regular variety.
     *
     * @param order the domain order with trading details
     * @return the Kite-assigned order ID
     * @throws BrokerException if the order is rejected or API call fails
     */
    @RateLimiter(name = "kiteOrders")
    @CircuitBreaker(name = "kiteApi")
    @Retry(name = "kiteApi")
    public String placeOrder(Order order) {
        OrderParams params = kiteOrderMapper.toOrderParams(order);
        try {
            com.zerodhatech.models.Order kiteOrder = kiteConnect.placeOrder(params, Constants.VARIETY_REGULAR);
            log.info(
                    "Order placed: orderId={} symbol={} side={} qty={}",
                    kiteOrder.orderId,
                    order.getTradingSymbol(),
                    order.getSide(),
                    order.getQuantity());
            return kiteOrder.orderId;
        } catch (KiteException e) {
            log.error("Kite order placement failed for {}: {}", order.getTradingSymbol(), e.message);
            throw new BrokerException("Order placement failed: " + e.message, e);
        } catch (JSONException | IOException e) {
            log.error("Order placement error for {}", order.getTradingSymbol(), e);
            throw new BrokerException("Order placement error: " + e.getMessage(), e);
        }
    }

    /**
     * Modifies an existing open order. Only price, triggerPrice, and quantity can be changed.
     *
     * @param brokerOrderId the Kite order ID
     * @param order         the updated order fields
     * @throws BrokerException if modification fails
     */
    @RateLimiter(name = "kiteOrders")
    @CircuitBreaker(name = "kiteApi")
    @Retry(name = "kiteApi")
    public void modifyOrder(String brokerOrderId, Order order) {
        OrderParams params = new OrderParams();
        if (order.getPrice() != null) {
            params.price = order.getPrice().doubleValue();
        }
        if (order.getTriggerPrice() != null) {
            params.triggerPrice = order.getTriggerPrice().doubleValue();
        }
        if (order.getQuantity() > 0) {
            params.quantity = order.getQuantity();
        }

        try {
            kiteConnect.modifyOrder(brokerOrderId, params, Constants.VARIETY_REGULAR);
            log.info("Order modified: orderId={}", brokerOrderId);
        } catch (KiteException e) {
            log.error("Order modification failed for {}: {}", brokerOrderId, e.message);
            throw new BrokerException("Order modification failed: " + e.message, e);
        } catch (JSONException | IOException e) {
            log.error("Order modification error for {}", brokerOrderId, e);
            throw new BrokerException("Order modification error: " + e.getMessage(), e);
        }
    }

    /**
     * Cancels an open order.
     *
     * @param brokerOrderId the Kite order ID to cancel
     * @throws BrokerException if cancellation fails
     */
    @RateLimiter(name = "kiteOrders")
    @CircuitBreaker(name = "kiteApi")
    public void cancelOrder(String brokerOrderId) {
        try {
            kiteConnect.cancelOrder(brokerOrderId, Constants.VARIETY_REGULAR);
            log.info("Order cancelled: orderId={}", brokerOrderId);
        } catch (KiteException e) {
            log.error("Order cancellation failed for {}: {}", brokerOrderId, e.message);
            throw new BrokerException("Order cancellation failed: " + e.message, e);
        } catch (JSONException | IOException e) {
            log.error("Order cancellation error for {}", brokerOrderId, e);
            throw new BrokerException("Order cancellation error: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves all orders for the current trading day.
     *
     * @return list of all orders mapped to domain model
     */
    @RateLimiter(name = "kiteOrders")
    @CircuitBreaker(name = "kiteApi")
    @Retry(name = "kiteApi")
    public List<Order> getOrders() {
        try {
            List<com.zerodhatech.models.Order> kiteOrders = kiteConnect.getOrders();
            return kiteOrderMapper.toDomainList(kiteOrders);
        } catch (KiteException e) {
            log.error("Failed to fetch orders: {}", e.message);
            throw new BrokerException("Failed to fetch orders: " + e.message, e);
        } catch (JSONException | IOException e) {
            log.error("Error fetching orders", e);
            throw new BrokerException("Error fetching orders: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves the history of a specific order (all state transitions).
     *
     * @param brokerOrderId the Kite order ID
     * @return order history entries in chronological order
     */
    @RateLimiter(name = "kiteOrders")
    @CircuitBreaker(name = "kiteApi")
    @Retry(name = "kiteApi")
    public List<Order> getOrderHistory(String brokerOrderId) {
        try {
            List<com.zerodhatech.models.Order> history = kiteConnect.getOrderHistory(brokerOrderId);
            return kiteOrderMapper.toDomainList(history);
        } catch (KiteException e) {
            log.error("Failed to fetch order history for {}: {}", brokerOrderId, e.message);
            throw new BrokerException("Failed to fetch order history: " + e.message, e);
        } catch (JSONException | IOException e) {
            log.error("Error fetching order history for {}", brokerOrderId, e);
            throw new BrokerException("Error fetching order history: " + e.getMessage(), e);
        }
    }

    /**
     * Places an order bypassing rate limiting. Used exclusively by the kill switch
     * to ensure emergency exits are never blocked by rate limits.
     *
     * <p>Circuit breaker is still active — if Kite is truly down, we shouldn't
     * hammer it. But rate limiting is removed so kill switch orders go through immediately.
     *
     * @param order the domain order with trading details
     * @return the Kite-assigned order ID
     */
    @CircuitBreaker(name = "kiteApi")
    public String placeOrderBypassRateLimit(Order order) {
        OrderParams params = kiteOrderMapper.toOrderParams(order);
        try {
            com.zerodhatech.models.Order kiteOrder = kiteConnect.placeOrder(params, Constants.VARIETY_REGULAR);
            log.info(
                    "Kill switch order placed: orderId={} symbol={} side={} qty={}",
                    kiteOrder.orderId,
                    order.getTradingSymbol(),
                    order.getSide(),
                    order.getQuantity());
            return kiteOrder.orderId;
        } catch (KiteException e) {
            log.error("Kill switch order failed for {}: {}", order.getTradingSymbol(), e.message);
            throw new BrokerException("Kill switch order failed: " + e.message, e);
        } catch (JSONException | IOException e) {
            log.error("Kill switch order error for {}", order.getTradingSymbol(), e);
            throw new BrokerException("Kill switch order error: " + e.getMessage(), e);
        }
    }

    /**
     * Cancels an order bypassing rate limiting. Used exclusively by the kill switch.
     *
     * @param brokerOrderId the Kite order ID to cancel
     */
    @CircuitBreaker(name = "kiteApi")
    public void cancelOrderBypassRateLimit(String brokerOrderId) {
        try {
            kiteConnect.cancelOrder(brokerOrderId, Constants.VARIETY_REGULAR);
            log.info("Kill switch cancel: orderId={}", brokerOrderId);
        } catch (KiteException e) {
            // Kill switch best-effort: log but don't throw, continue with next order
            log.error("Kill switch cancel failed for {}: {}", brokerOrderId, e.message);
        } catch (JSONException | IOException e) {
            log.error("Kill switch cancel error for {}", brokerOrderId, e);
        }
    }
}
