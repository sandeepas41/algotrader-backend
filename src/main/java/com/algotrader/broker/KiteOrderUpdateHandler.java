package com.algotrader.broker;

import com.algotrader.broker.mapper.KiteOrderMapper;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.model.Order;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.reconciliation.PositionReconciliationService;
import com.algotrader.repository.redis.OrderRedisRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Processes Kite WebSocket order status updates (fills and rejections).
 *
 * <p>Receives raw {@link com.zerodhatech.models.Order} objects from the KiteTicker's
 * OnOrderUpdate callback, determines what changed, and publishes the appropriate
 * domain events (FILLED, PARTIALLY_FILLED, REJECTED).
 *
 * <p>Safety guarantees:
 * <ul>
 *   <li>Only acts on fills (increased filledQuantity) and rejections; all other
 *       status changes are logged and ignored</li>
 *   <li>Idempotent: compares filledQuantity against stored order to detect actual fills</li>
 *   <li>Never transitions a terminal order (COMPLETE/REJECTED/CANCELLED) backwards</li>
 *   <li>Triggers immediate position reconciliation on full fills only</li>
 * </ul>
 */
@Service
public class KiteOrderUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(KiteOrderUpdateHandler.class);

    /** Terminal statuses that must never be overwritten by an incoming update. */
    private static final Set<OrderStatus> TERMINAL_STATUSES =
            Set.of(OrderStatus.COMPLETE, OrderStatus.REJECTED, OrderStatus.CANCELLED);

    private final OrderRedisRepository orderRedisRepository;
    private final KiteOrderMapper kiteOrderMapper;
    private final EventPublisherHelper eventPublisherHelper;
    private final PositionReconciliationService positionReconciliationService;

    public KiteOrderUpdateHandler(
            OrderRedisRepository orderRedisRepository,
            KiteOrderMapper kiteOrderMapper,
            EventPublisherHelper eventPublisherHelper,
            PositionReconciliationService positionReconciliationService) {
        this.orderRedisRepository = orderRedisRepository;
        this.kiteOrderMapper = kiteOrderMapper;
        this.eventPublisherHelper = eventPublisherHelper;
        this.positionReconciliationService = positionReconciliationService;
    }

    /**
     * Processes a Kite WebSocket order update.
     *
     * <p>Called from the KiteTicker's OnOrderUpdate callback thread.
     * This method must be safe to call from any thread.
     *
     * <p>Routing logic:
     * <ol>
     *   <li>REJECTED → update status and publish rejected event</li>
     *   <li>COMPLETE → publish filled event if filledQuantity increased, trigger position sync</li>
     *   <li>Any other status with increased filledQuantity → publish partially filled event</li>
     *   <li>Everything else → log and ignore</li>
     * </ol>
     */
    public void handleOrderUpdate(com.zerodhatech.models.Order kiteOrder) {
        if (kiteOrder == null || kiteOrder.orderId == null) {
            log.warn("Received null order update or order with null orderId, ignoring");
            return;
        }

        OrderStatus incomingStatus = kiteOrderMapper.mapStatus(kiteOrder.status);
        int incomingFilledQty = parseIntSafe(kiteOrder.filledQuantity);
        String brokerOrderId = kiteOrder.orderId;

        log.debug(
                "Order update received: brokerOrderId={}, status={}, filledQty={}, avgPrice={}",
                brokerOrderId,
                kiteOrder.status,
                kiteOrder.filledQuantity,
                kiteOrder.averagePrice);

        // Look up our internal order by Kite's broker order ID
        Optional<Order> existingOpt = orderRedisRepository.findByBrokerOrderId(brokerOrderId);
        if (existingOpt.isEmpty()) {
            // Order placed outside our system (manual via Kite web), or not yet saved to Redis
            log.info(
                    "Order update for unknown brokerOrderId={}, status={} -- ignoring (not our order or not yet saved)",
                    brokerOrderId,
                    kiteOrder.status);
            return;
        }

        Order existingOrder = existingOpt.get();

        // Safety: never transition a terminal order backwards
        if (TERMINAL_STATUSES.contains(existingOrder.getStatus())) {
            log.debug(
                    "Order {} already in terminal status {}, ignoring update to {}",
                    brokerOrderId,
                    existingOrder.getStatus(),
                    incomingStatus);
            return;
        }

        // Route by incoming status
        if (incomingStatus == OrderStatus.REJECTED) {
            handleRejection(existingOrder, kiteOrder);
        } else if (incomingStatus == OrderStatus.COMPLETE) {
            handleComplete(existingOrder, kiteOrder, incomingFilledQty);
        } else if (incomingFilledQty > existingOrder.getFilledQuantity()) {
            // Any non-terminal status with increased filledQuantity = partial fill
            handlePartialFill(existingOrder, kiteOrder, incomingFilledQty);
        } else {
            log.debug(
                    "Ignoring order update: brokerOrderId={}, status={}, no fill change",
                    brokerOrderId,
                    kiteOrder.status);
        }
    }

    /**
     * Handles a REJECTED status update from Kite.
     */
    private void handleRejection(Order existingOrder, com.zerodhatech.models.Order kiteOrder) {
        existingOrder.setStatus(OrderStatus.REJECTED);
        existingOrder.setRejectionReason(kiteOrder.statusMessage);
        existingOrder.setUpdatedAt(LocalDateTime.now());

        orderRedisRepository.save(existingOrder);
        eventPublisherHelper.publishOrderRejected(this, existingOrder);

        log.warn(
                "Order rejected by broker: brokerOrderId={}, symbol={}, reason={}",
                kiteOrder.orderId,
                existingOrder.getTradingSymbol(),
                kiteOrder.statusMessage);
    }

    /**
     * Handles a COMPLETE status update (fully filled order).
     *
     * <p>Checks if filledQuantity actually increased to guard against duplicate events.
     * Triggers position reconciliation to sync local positions with broker.
     */
    private void handleComplete(Order existingOrder, com.zerodhatech.models.Order kiteOrder, int newFilledQty) {
        int previousFilledQty = existingOrder.getFilledQuantity();

        // Idempotency guard: only process if filledQuantity actually increased
        if (newFilledQty <= previousFilledQty) {
            log.debug(
                    "Duplicate COMPLETE event for brokerOrderId={}: filledQty unchanged ({} <= {})",
                    kiteOrder.orderId,
                    newFilledQty,
                    previousFilledQty);
            return;
        }

        OrderStatus previousStatus = existingOrder.getStatus();

        existingOrder.setFilledQuantity(newFilledQty);
        existingOrder.setAverageFillPrice(parseBigDecimalSafe(kiteOrder.averagePrice));
        existingOrder.setStatus(OrderStatus.COMPLETE);
        existingOrder.setUpdatedAt(LocalDateTime.now());

        orderRedisRepository.save(existingOrder);

        // Downstream OrderFillService listens for this and creates fill records
        eventPublisherHelper.publishOrderFilled(this, existingOrder, previousStatus);

        log.info(
                "Order filled: brokerOrderId={}, symbol={}, filledQty={}, avgPrice={}, previousFilled={}",
                kiteOrder.orderId,
                existingOrder.getTradingSymbol(),
                newFilledQty,
                kiteOrder.averagePrice,
                previousFilledQty);

        // Trigger immediate position reconciliation so positions appear in Redis within seconds
        triggerPositionSync();
    }

    /**
     * Handles a partial fill: any non-terminal status update where filledQuantity increased.
     *
     * <p>Does NOT trigger position reconciliation (waits for COMPLETE to avoid
     * excessive Kite API calls during rapid partial fills).
     */
    private void handlePartialFill(Order existingOrder, com.zerodhatech.models.Order kiteOrder, int newFilledQty) {
        int previousFilledQty = existingOrder.getFilledQuantity();

        // Idempotency guard (same as handleComplete)
        if (newFilledQty <= previousFilledQty) {
            log.debug(
                    "Duplicate partial fill for brokerOrderId={}: filledQty unchanged ({} <= {})",
                    kiteOrder.orderId,
                    newFilledQty,
                    previousFilledQty);
            return;
        }

        OrderStatus previousStatus = existingOrder.getStatus();

        existingOrder.setFilledQuantity(newFilledQty);
        existingOrder.setAverageFillPrice(parseBigDecimalSafe(kiteOrder.averagePrice));
        existingOrder.setStatus(OrderStatus.PARTIAL);
        existingOrder.setUpdatedAt(LocalDateTime.now());

        orderRedisRepository.save(existingOrder);

        // Downstream OrderFillService listens for this and creates fill records
        eventPublisherHelper.publishOrderPartiallyFilled(this, existingOrder, previousStatus);

        log.info(
                "Order partially filled: brokerOrderId={}, symbol={}, filledQty={}/{}, avgPrice={}",
                kiteOrder.orderId,
                existingOrder.getTradingSymbol(),
                newFilledQty,
                existingOrder.getQuantity(),
                kiteOrder.averagePrice);
    }

    /**
     * Triggers position reconciliation to sync local positions with broker state.
     * Wrapped in try/catch so reconciliation failure never disrupts order update processing.
     */
    private void triggerPositionSync() {
        try {
            positionReconciliationService.reconcile("ORDER_FILL");
        } catch (Exception e) {
            log.error("Failed to trigger position reconciliation after fill", e);
        }
    }

    private int parseIntSafe(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private BigDecimal parseBigDecimalSafe(String value) {
        if (value == null || value.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
