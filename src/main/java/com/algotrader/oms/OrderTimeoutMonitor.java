package com.algotrader.oms;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.repository.redis.OrderRedisRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monitors open orders for timeout and cancels them when their time limit expires.
 *
 * <p>Timeout rules per order type:
 * <ul>
 *   <li>MARKET: 10 seconds (should fill nearly instantly; timeout means something is wrong)</li>
 *   <li>LIMIT: 30 seconds (short-lived LIMIT orders for strategy entries/adjustments)</li>
 *   <li>SL/SL_M: aligned to current trading session end (15:30 IST). These orders sit
 *       waiting for their trigger price and should not be cancelled prematurely. Kite
 *       cancels bracket/cover orders at day end anyway, so we align our timeout to the
 *       session close rather than using an arbitrary 24h window.</li>
 * </ul>
 *
 * <p>On timeout, the order is cancelled via BrokerGateway and a CANCELLED event is published.
 * If the order belongs to a strategy, the strategy engine receives the cancellation event
 * and can decide to re-enter or adjust.
 *
 * <p>Runs every 5 seconds via {@code @Scheduled(fixedRate = 5000)}.
 */
@Component
public class OrderTimeoutMonitor {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutMonitor.class);

    /** Timeout durations for MARKET and LIMIT order types. */
    static final Map<OrderType, Duration> TIMEOUT_BY_TYPE = Map.of(
            OrderType.MARKET, Duration.ofSeconds(10),
            OrderType.LIMIT, Duration.ofSeconds(30));

    private final OrderRedisRepository orderRedisRepository;
    private final BrokerGateway brokerGateway;
    private final TradingCalendarService tradingCalendarService;
    private final EventPublisherHelper eventPublisherHelper;

    public OrderTimeoutMonitor(
            OrderRedisRepository orderRedisRepository,
            BrokerGateway brokerGateway,
            TradingCalendarService tradingCalendarService,
            EventPublisherHelper eventPublisherHelper) {
        this.orderRedisRepository = orderRedisRepository;
        this.brokerGateway = brokerGateway;
        this.tradingCalendarService = tradingCalendarService;
        this.eventPublisherHelper = eventPublisherHelper;
    }

    /**
     * Checks all pending orders for timeout every 5 seconds.
     * Only runs during active market hours to avoid wasting cycles.
     */
    @Scheduled(fixedRate = 5000)
    public void checkTimeouts() {
        List<Order> pendingOrders = orderRedisRepository.findPending();

        if (pendingOrders.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        for (Order order : pendingOrders) {
            if (isTimedOut(order, now)) {
                handleTimeout(order, now);
            }
        }
    }

    /**
     * Determines if an order has exceeded its timeout.
     *
     * <p>For SL/SL_M orders, the timeout aligns with the trading session end (15:30 IST)
     * because these orders are meant to sit until their trigger price is hit.
     * Cancelling them prematurely would leave positions unprotected.
     *
     * @param order the order to check
     * @param now   the current timestamp
     * @return true if the order has timed out
     */
    public boolean isTimedOut(Order order, LocalDateTime now) {
        if (order.getPlacedAt() == null) {
            return false;
        }

        Duration timeout = getTimeoutForOrder(order, now);
        Duration elapsed = Duration.between(order.getPlacedAt(), now);

        return elapsed.compareTo(timeout) > 0;
    }

    /**
     * Returns the timeout duration for a given order.
     *
     * <p>SL and SL_M orders use session-end-aligned timeout: the time remaining
     * until market close (15:30 IST). This prevents premature cancellation of
     * stop-loss orders that protect open positions.
     */
    public Duration getTimeoutForOrder(Order order, LocalDateTime now) {
        OrderType type = order.getType();

        if (type == OrderType.SL || type == OrderType.SL_M) {
            return getSessionEndTimeout(now);
        }

        return TIMEOUT_BY_TYPE.getOrDefault(type, Duration.ofSeconds(30));
    }

    /**
     * Calculates the duration until the current trading session ends (15:30 IST).
     *
     * <p>If the market is already closed or the session end has passed,
     * returns zero (meaning the order should be cancelled immediately).
     */
    Duration getSessionEndTimeout(LocalDateTime now) {
        long minutesToClose = tradingCalendarService.getMinutesToClose();

        if (minutesToClose <= 0) {
            // Market already closed or past closing time -- cancel immediately
            return Duration.ZERO;
        }

        return Duration.ofMinutes(minutesToClose);
    }

    /**
     * Handles a timed-out order: cancels it via the broker and publishes events.
     */
    void handleTimeout(Order order, LocalDateTime now) {
        Duration elapsed = Duration.between(order.getPlacedAt(), now);

        log.warn(
                "Order timeout: brokerOrderId={}, type={}, elapsed={}s, symbol={}",
                order.getBrokerOrderId(),
                order.getType(),
                elapsed.toSeconds(),
                order.getTradingSymbol());

        try {
            brokerGateway.cancelOrder(order.getBrokerOrderId());

            OrderStatus previousStatus = order.getStatus();
            order.setStatus(OrderStatus.CANCELLED);
            order.setUpdatedAt(now);
            orderRedisRepository.save(order);

            eventPublisherHelper.publishOrderCancelled(this, order, previousStatus);

            eventPublisherHelper.publishDecision(
                    this,
                    "ORDER",
                    String.format(
                            "Order timed out and cancelled: %s %s %d x %s (elapsed=%ds, type=%s)",
                            order.getSide(),
                            order.getType(),
                            order.getQuantity(),
                            order.getTradingSymbol(),
                            elapsed.toSeconds(),
                            order.getType()),
                    order.getStrategyId(),
                    Map.of(
                            "brokerOrderId", nullSafe(order.getBrokerOrderId()),
                            "correlationId", nullSafe(order.getCorrelationId()),
                            "timeoutSeconds", elapsed.toSeconds(),
                            "orderType", order.getType().name()));

        } catch (Exception e) {
            log.error(
                    "Failed to cancel timed-out order: brokerOrderId={}, symbol={}",
                    order.getBrokerOrderId(),
                    order.getTradingSymbol(),
                    e);
        }
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
