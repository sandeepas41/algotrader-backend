package com.algotrader.event;

import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.model.Order;
import org.springframework.context.ApplicationEvent;

/**
 * Published when an order state changes (placed, filled, cancelled, rejected, etc.).
 *
 * <p>Order events are published by the OrderService and KiteBrokerGateway when
 * Kite WebSocket pushes real-time order status updates. Each event carries the
 * current order snapshot, the type of change, and the previous status for
 * transition-aware processing.
 *
 * <p>Key listeners:
 * <ul>
 *   <li>PositionService — updates positions on FILLED events</li>
 *   <li>StrategyService — tracks strategy leg status</li>
 *   <li>AlertService — sends order notifications</li>
 *   <li>AuditService — logs for compliance</li>
 *   <li>WebSocketHandler — pushes to frontend</li>
 * </ul>
 */
public class OrderEvent extends ApplicationEvent {

    private final Order order;
    private final OrderEventType eventType;
    private final OrderStatus previousStatus;

    /**
     * Creates an OrderEvent with a known previous status.
     *
     * @param source         the component publishing this event
     * @param order          the current order snapshot (immutable after creation)
     * @param eventType      what kind of state change occurred
     * @param previousStatus the order's status before this change (null if unknown)
     */
    public OrderEvent(Object source, Order order, OrderEventType eventType, OrderStatus previousStatus) {
        super(source);
        this.order = order;
        this.eventType = eventType;
        this.previousStatus = previousStatus;
    }

    /**
     * Creates an OrderEvent without a previous status (e.g., for PLACED events).
     */
    public OrderEvent(Object source, Order order, OrderEventType eventType) {
        this(source, order, eventType, null);
    }

    public Order getOrder() {
        return order;
    }

    public OrderEventType getEventType() {
        return eventType;
    }

    /**
     * The order's status before this event. May be null for PLACED events
     * where there is no previous status.
     */
    public OrderStatus getPreviousStatus() {
        return previousStatus;
    }
}
