package com.algotrader.event;

import com.algotrader.domain.model.AdjustmentAction;
import com.algotrader.domain.model.AdjustmentRule;
import com.algotrader.domain.model.Position;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a strategy adjustment is triggered by an adjustment rule.
 *
 * <p>Adjustment events are published by BaseStrategy (via inline adjust() method)
 * when an adjustment rule's conditions are met. The event carries the strategy ID,
 * the triggering rule, the planned action, and the affected position.
 *
 * <p>Key listeners:
 * <ul>
 *   <li>OrderService — executes adjustment orders</li>
 *   <li>AlertService — sends adjustment notifications</li>
 *   <li>AuditService — logs adjustment audit trail</li>
 * </ul>
 */
public class AdjustmentEvent extends ApplicationEvent {

    private final String strategyId;
    private final AdjustmentRule rule;
    private final AdjustmentAction action;
    private final Position affectedPosition;
    private final AdjustmentStatus status;

    public AdjustmentEvent(
            Object source, String strategyId, AdjustmentRule rule, AdjustmentAction action, Position affectedPosition) {
        super(source);
        this.strategyId = strategyId;
        this.rule = rule;
        this.action = action;
        this.affectedPosition = affectedPosition;
        this.status = AdjustmentStatus.PENDING;
    }

    public AdjustmentEvent(
            Object source,
            String strategyId,
            AdjustmentRule rule,
            AdjustmentAction action,
            Position affectedPosition,
            AdjustmentStatus status) {
        super(source);
        this.strategyId = strategyId;
        this.rule = rule;
        this.action = action;
        this.affectedPosition = affectedPosition;
        this.status = status;
    }

    public String getStrategyId() {
        return strategyId;
    }

    public AdjustmentRule getRule() {
        return rule;
    }

    public AdjustmentAction getAction() {
        return action;
    }

    /**
     * The position being adjusted. May be null if the adjustment is adding
     * a new hedge leg rather than modifying an existing position.
     */
    public Position getAffectedPosition() {
        return affectedPosition;
    }

    public AdjustmentStatus getStatus() {
        return status;
    }
}
