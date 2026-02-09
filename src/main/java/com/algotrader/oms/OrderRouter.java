package com.algotrader.oms;

import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.event.EventPublisherHelper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Single entry point for all order placement in the system.
 *
 * <p>Every order -- whether from a strategy engine, manual UI submission, or kill switch
 * operation -- MUST flow through {@link #route(OrderRequest, OrderPriority)}. This ensures
 * consistent validation, idempotency checking, kill switch gating, and decision logging
 * regardless of the order source.
 *
 * <p>Routing pipeline:
 * <ol>
 *   <li>Kill switch check -- if active, reject all non-KILL_SWITCH orders</li>
 *   <li>Idempotency check -- reject duplicates within the 5-min dedup window</li>
 *   <li>Risk validation (#TODO Task 7.1 -- RiskManager integration)</li>
 *   <li>Enqueue to {@link OrderQueue} for priority-based execution</li>
 *   <li>Mark idempotency key</li>
 *   <li>Log decision</li>
 * </ol>
 *
 * <p>Execution is handled asynchronously by {@link OrderQueueProcessor} which drains
 * the queue and places orders via the BrokerGateway. The router returns immediately
 * after validation and enqueue.
 *
 * <p>Decision logging records every routing decision (acceptance or rejection) with
 * the correlationId from the OrderRequest for end-to-end tracing.
 */
@Service
public class OrderRouter {

    private static final Logger log = LoggerFactory.getLogger(OrderRouter.class);

    private final IdempotencyService idempotencyService;
    private final OrderQueue orderQueue;
    private final EventPublisherHelper eventPublisherHelper;

    /**
     * Kill switch state. When active, all non-KILL_SWITCH orders are rejected.
     * This is a lightweight in-process flag; the full KillSwitch service (Phase 7)
     * will activate/deactivate this via {@link #activateKillSwitch()} and
     * {@link #deactivateKillSwitch()}.
     */
    private final AtomicBoolean killSwitchActive = new AtomicBoolean(false);

    public OrderRouter(
            IdempotencyService idempotencyService, OrderQueue orderQueue, EventPublisherHelper eventPublisherHelper) {
        this.idempotencyService = idempotencyService;
        this.orderQueue = orderQueue;
        this.eventPublisherHelper = eventPublisherHelper;
    }

    /**
     * Routes an order through the full validation pipeline and enqueues it for execution.
     *
     * <p>This is the ONLY method through which orders should be placed. Each
     * rejection or acceptance is logged as a DecisionEvent with the correlationId
     * for end-to-end tracing.
     *
     * <p>On success, the order is enqueued and will be executed asynchronously by
     * {@link OrderQueueProcessor}. The returned result indicates the order was accepted
     * into the queue but does not yet have a broker order ID.
     *
     * @param orderRequest the order parameters
     * @param priority     determines queue position and whether checks are bypassed
     * @return result indicating acceptance (enqueued) or rejection (with reason)
     */
    public OrderRouteResult route(OrderRequest orderRequest, OrderPriority priority) {
        String correlationId = orderRequest.getCorrelationId();

        // Step 1: Kill switch check (kill switch orders themselves bypass this)
        if (priority != OrderPriority.KILL_SWITCH && killSwitchActive.get()) {
            String reason = "Kill switch is active, no new orders allowed";
            log.warn(
                    "Order rejected: {} [correlationId={}, symbol={}]",
                    reason,
                    correlationId,
                    orderRequest.getTradingSymbol());
            logDecision("ORDER", "Order rejected: " + reason, orderRequest, priority, false);
            return OrderRouteResult.rejected(reason);
        }

        // Step 2: Idempotency check
        if (!idempotencyService.isUnique(orderRequest)) {
            String reason = "Duplicate order detected within deduplication window";
            log.warn(
                    "Order rejected: {} [correlationId={}, symbol={}]",
                    reason,
                    correlationId,
                    orderRequest.getTradingSymbol());
            logDecision("ORDER", "Order rejected: " + reason, orderRequest, priority, false);
            return OrderRouteResult.rejected(reason);
        }

        // Step 3: Risk validation
        // #TODO Task 7.1 -- integrate RiskManager.validateOrder(orderRequest) here
        // For now, all orders pass risk checks. When RiskManager is implemented:
        //   RiskValidationResult riskResult = riskManager.validateOrder(orderRequest);
        //   if (!riskResult.isApproved()) { return OrderRouteResult.rejected(...); }

        // Step 4: Enqueue for priority-based execution
        orderQueue.enqueue(orderRequest, priority);

        // Step 5: Mark idempotency key after successful enqueue
        idempotencyService.markProcessed(orderRequest);

        // Step 6: Log acceptance decision
        logDecision(
                "ORDER",
                String.format(
                        "Order enqueued: %s %s %d x %s @ %s [priority=%s]",
                        orderRequest.getSide(),
                        orderRequest.getType(),
                        orderRequest.getQuantity(),
                        orderRequest.getTradingSymbol(),
                        orderRequest.getPrice() != null ? orderRequest.getPrice() : "MARKET",
                        priority),
                orderRequest,
                priority,
                true);

        log.info(
                "Order routed: priority={}, symbol={}, queueSize={}",
                priority,
                orderRequest.getTradingSymbol(),
                orderQueue.size());

        return OrderRouteResult.accepted(null);
    }

    /**
     * Activates the kill switch. When active, all non-KILL_SWITCH orders are rejected.
     * Called by KillSwitch service (Task 7.2) or via REST API.
     */
    public void activateKillSwitch() {
        killSwitchActive.set(true);
        log.warn("Kill switch ACTIVATED -- all non-emergency orders will be rejected");
        eventPublisherHelper.publishDecision(this, "SYSTEM", "Kill switch activated on OrderRouter");
    }

    /**
     * Deactivates the kill switch. Normal order flow resumes.
     */
    public void deactivateKillSwitch() {
        killSwitchActive.set(false);
        log.info("Kill switch DEACTIVATED -- normal order flow resumed");
        eventPublisherHelper.publishDecision(this, "SYSTEM", "Kill switch deactivated on OrderRouter");
    }

    /** Returns whether the kill switch is currently active. */
    public boolean isKillSwitchActive() {
        return killSwitchActive.get();
    }

    /**
     * Publishes a decision event for order routing, including correlationId and priority
     * for end-to-end tracing.
     */
    private void logDecision(
            String category, String message, OrderRequest orderRequest, OrderPriority priority, boolean accepted) {
        Map<String, Object> context = Map.of(
                "correlationId", nullSafe(orderRequest.getCorrelationId()),
                "strategyId", nullSafe(orderRequest.getStrategyId()),
                "symbol", nullSafe(orderRequest.getTradingSymbol()),
                "side", orderRequest.getSide() != null ? orderRequest.getSide().name() : "UNKNOWN",
                "priority", priority.name(),
                "accepted", accepted);

        eventPublisherHelper.publishDecision(this, category, message, orderRequest.getStrategyId(), context);
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
