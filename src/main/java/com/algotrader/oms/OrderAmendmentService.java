package com.algotrader.oms;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.domain.enums.AmendmentStatus;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.model.Order;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.repository.redis.OrderRedisRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Manages the order amendment (modification) lifecycle.
 *
 * <p>Provides a state machine for modifying open orders:
 * <pre>
 * NONE → MODIFY_REQUESTED → MODIFY_SENT → MODIFY_CONFIRMED | MODIFY_REJECTED
 * </pre>
 *
 * <p>Validation rules:
 * <ul>
 *   <li>Only OPEN or TRIGGER_PENDING orders can be modified</li>
 *   <li>Order must not have an amendment already in progress (MODIFY_REQUESTED or MODIFY_SENT)</li>
 *   <li>At least one modification field must be non-null</li>
 *   <li>Prices must be positive if provided</li>
 *   <li>Quantity must exceed already-filled quantity if provided</li>
 * </ul>
 *
 * <p>The service updates the order in Redis at each state transition for real-time
 * visibility. Broker communication is via {@link BrokerGateway#modifyOrder(String, Order)}.
 *
 * <p>Decision events are logged at each transition for audit trail.
 */
@Service
public class OrderAmendmentService {

    private static final Logger log = LoggerFactory.getLogger(OrderAmendmentService.class);

    private final OrderRedisRepository orderRedisRepository;
    private final BrokerGateway brokerGateway;
    private final EventPublisherHelper eventPublisherHelper;

    public OrderAmendmentService(
            OrderRedisRepository orderRedisRepository,
            BrokerGateway brokerGateway,
            EventPublisherHelper eventPublisherHelper) {
        this.orderRedisRepository = orderRedisRepository;
        this.brokerGateway = brokerGateway;
        this.eventPublisherHelper = eventPublisherHelper;
    }

    /**
     * Modifies an existing open order.
     *
     * <p>Transitions the amendment status through the state machine:
     * NONE → MODIFY_REQUESTED → MODIFY_SENT → MODIFY_CONFIRMED (or MODIFY_REJECTED).
     *
     * <p>If the broker rejects the modification, the order retains its original parameters
     * and the amendment status is set to MODIFY_REJECTED with the reason.
     *
     * @param orderId      the internal order ID (Redis key)
     * @param modification the requested changes (price, triggerPrice, quantity)
     * @return result indicating success or rejection with reason
     */
    public OrderAmendmentResult modifyOrder(String orderId, OrderModification modification) {
        // Step 1: Fetch order from Redis
        Optional<Order> optionalOrder = orderRedisRepository.findById(orderId);
        if (optionalOrder.isEmpty()) {
            return OrderAmendmentResult.rejected("Order not found: " + orderId);
        }

        Order order = optionalOrder.get();

        // Step 2: Validate state allows modification
        String stateValidation = validateOrderState(order);
        if (stateValidation != null) {
            logDecision(order, "Amendment rejected: " + stateValidation, false);
            return OrderAmendmentResult.rejected(stateValidation);
        }

        // Step 3: Validate modification parameters
        String paramValidation = validateModificationParams(modification, order);
        if (paramValidation != null) {
            logDecision(order, "Amendment rejected: " + paramValidation, false);
            return OrderAmendmentResult.rejected(paramValidation);
        }

        // Step 4: Transition to MODIFY_REQUESTED
        order.setAmendmentStatus(AmendmentStatus.MODIFY_REQUESTED);
        order.setUpdatedAt(LocalDateTime.now());
        orderRedisRepository.save(order);

        try {
            // Step 5: Build the modified order for the broker
            Order modifiedOrder = buildModifiedOrder(order, modification);

            // Step 6: Transition to MODIFY_SENT
            order.setAmendmentStatus(AmendmentStatus.MODIFY_SENT);
            orderRedisRepository.save(order);

            // Step 7: Send modification to broker
            brokerGateway.modifyOrder(order.getBrokerOrderId(), modifiedOrder);

            // Step 8: Transition to MODIFY_CONFIRMED
            applyModification(order, modification);
            order.setAmendmentStatus(AmendmentStatus.MODIFY_CONFIRMED);
            order.setAmendmentRejectReason(null);
            order.setUpdatedAt(LocalDateTime.now());
            orderRedisRepository.save(order);

            // Publish event
            eventPublisherHelper.publishOrderModified(this, order);

            logDecision(
                    order,
                    String.format(
                            "Order modified: price=%s, triggerPrice=%s, qty=%s",
                            modification.getPrice(), modification.getTriggerPrice(), modification.getQuantity()),
                    true);

            log.info(
                    "Order modified: brokerOrderId={}, newPrice={}, newTrigger={}, newQty={}",
                    order.getBrokerOrderId(),
                    modification.getPrice(),
                    modification.getTriggerPrice(),
                    modification.getQuantity());

            // Reset amendment status to NONE so future modifications are allowed
            order.setAmendmentStatus(AmendmentStatus.NONE);
            orderRedisRepository.save(order);

            return OrderAmendmentResult.success(orderId);

        } catch (Exception e) {
            // Step: Transition to MODIFY_REJECTED
            order.setAmendmentStatus(AmendmentStatus.MODIFY_REJECTED);
            order.setAmendmentRejectReason(e.getMessage());
            order.setUpdatedAt(LocalDateTime.now());
            orderRedisRepository.save(order);

            logDecision(order, "Amendment failed: " + e.getMessage(), false);

            log.error(
                    "Order modification failed: brokerOrderId={}, reason={}",
                    order.getBrokerOrderId(),
                    e.getMessage(),
                    e);

            return OrderAmendmentResult.rejected(e.getMessage());
        }
    }

    /**
     * Validates that the order is in a state that allows modification.
     *
     * @return null if valid, or a rejection reason string
     */
    public String validateOrderState(Order order) {
        // Must be OPEN or TRIGGER_PENDING
        if (order.getStatus() != OrderStatus.OPEN && order.getStatus() != OrderStatus.TRIGGER_PENDING) {
            return "Order is not in a modifiable state: " + order.getStatus();
        }

        // Must not have an amendment already in progress
        AmendmentStatus currentAmendment = order.getAmendmentStatus();
        if (currentAmendment == AmendmentStatus.MODIFY_REQUESTED || currentAmendment == AmendmentStatus.MODIFY_SENT) {
            return "Amendment already in progress: " + currentAmendment;
        }

        return null;
    }

    /**
     * Validates the modification parameters.
     *
     * @return null if valid, or a rejection reason string
     */
    public String validateModificationParams(OrderModification modification, Order order) {
        // At least one field must be set
        if (modification.getPrice() == null
                && modification.getTriggerPrice() == null
                && modification.getQuantity() == null) {
            return "At least one modification field must be provided";
        }

        // Price must be positive
        if (modification.getPrice() != null && modification.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return "Invalid modification price: must be positive";
        }

        // Trigger price must be positive
        if (modification.getTriggerPrice() != null
                && modification.getTriggerPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return "Invalid trigger price: must be positive";
        }

        // Quantity must exceed already-filled quantity
        if (modification.getQuantity() != null) {
            if (modification.getQuantity() <= 0) {
                return "Invalid modification quantity: must be positive";
            }
            if (modification.getQuantity() <= order.getFilledQuantity()) {
                return String.format(
                        "New quantity (%d) must exceed already filled quantity (%d)",
                        modification.getQuantity(), order.getFilledQuantity());
            }
        }

        return null;
    }

    /**
     * Builds an Order object with the modified fields for sending to the broker.
     * Only the fields being modified are set; the rest come from the original order.
     */
    private Order buildModifiedOrder(Order original, OrderModification modification) {
        return Order.builder()
                .price(modification.getPrice() != null ? modification.getPrice() : original.getPrice())
                .triggerPrice(
                        modification.getTriggerPrice() != null
                                ? modification.getTriggerPrice()
                                : original.getTriggerPrice())
                .quantity(modification.getQuantity() != null ? modification.getQuantity() : original.getQuantity())
                .build();
    }

    /**
     * Applies the modification to the order's fields.
     */
    private void applyModification(Order order, OrderModification modification) {
        if (modification.getPrice() != null) {
            order.setPrice(modification.getPrice());
        }
        if (modification.getTriggerPrice() != null) {
            order.setTriggerPrice(modification.getTriggerPrice());
        }
        if (modification.getQuantity() != null) {
            order.setQuantity(modification.getQuantity());
        }
    }

    private void logDecision(Order order, String message, boolean accepted) {
        eventPublisherHelper.publishDecision(
                this,
                "ORDER",
                message,
                order.getStrategyId(),
                Map.of(
                        "brokerOrderId", nullSafe(order.getBrokerOrderId()),
                        "correlationId", nullSafe(order.getCorrelationId()),
                        "accepted", accepted));
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
