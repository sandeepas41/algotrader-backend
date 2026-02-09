package com.algotrader.event;

import com.algotrader.domain.model.ConditionRule;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a condition rule fires (indicator crosses threshold).
 *
 * <p>Consumed by:
 * <ul>
 *   <li>{@code ConditionStreamHandler} -- pushes trigger notification to frontend via WebSocket</li>
 *   <li>{@code DecisionLogger} -- records the trigger with full indicator context</li>
 * </ul>
 *
 * <p>Note: Strategy deployment is handled inline by the ConditionEngine
 * (not via this event) to ensure synchronous error handling and trigger
 * count updates. This event is for notification/logging only.
 */
public class ConditionTriggeredEvent extends ApplicationEvent {

    private final ConditionRule conditionRule;
    private final BigDecimal indicatorValue;
    private final LocalDateTime triggeredAt;

    public ConditionTriggeredEvent(Object source, ConditionRule conditionRule, BigDecimal indicatorValue) {
        super(source);
        this.conditionRule = conditionRule;
        this.indicatorValue = indicatorValue;
        this.triggeredAt = LocalDateTime.now();
    }

    public ConditionRule getConditionRule() {
        return conditionRule;
    }

    public BigDecimal getIndicatorValue() {
        return indicatorValue;
    }

    public LocalDateTime getTriggeredAt() {
        return triggeredAt;
    }
}
