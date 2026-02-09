package com.algotrader.event;

import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.model.Strategy;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a strategy lifecycle transition occurs.
 *
 * <p>Strategy events track the full lifecycle from creation through deployment,
 * adjustments, and closure. They are published by the StrategyService and
 * StrategyEngine.
 *
 * <p>Key listeners:
 * <ul>
 *   <li>AlertService — sends strategy state change notifications</li>
 *   <li>AuditService — logs strategy audit trail</li>
 *   <li>WebSocketHandler — pushes strategy updates to frontend</li>
 * </ul>
 */
public class StrategyEvent extends ApplicationEvent {

    private final Strategy strategy;
    private final StrategyEventType eventType;
    private final StrategyStatus previousStatus;

    public StrategyEvent(Object source, Strategy strategy, StrategyEventType eventType, StrategyStatus previousStatus) {
        super(source);
        this.strategy = strategy;
        this.eventType = eventType;
        this.previousStatus = previousStatus;
    }

    public StrategyEvent(Object source, Strategy strategy, StrategyEventType eventType) {
        this(source, strategy, eventType, null);
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public StrategyEventType getEventType() {
        return eventType;
    }

    /**
     * The strategy's status before this event. Null for CREATED events.
     */
    public StrategyStatus getPreviousStatus() {
        return previousStatus;
    }
}
