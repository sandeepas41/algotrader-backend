package com.algotrader.event;

import com.algotrader.domain.model.DecisionRecord;
import org.springframework.context.ApplicationEvent;

/**
 * Published by the {@code DecisionLogger} each time a decision is logged.
 *
 * <p>Listeners use this event to stream decision records to the frontend via
 * WebSocket (Task 8.2) and to any other observer that needs real-time
 * decision data (e.g., metrics, alerting).
 *
 * <p>This event carries the full {@link DecisionRecord} domain model,
 * not the JPA entity. WebSocket handlers should map it to a DTO before sending.
 *
 * <p>Note: This is distinct from the older {@link DecisionEvent} which uses
 * string-based category/message fields. DecisionLogEvent uses the richer
 * enum-based DecisionRecord model introduced in Task 8.1.
 */
public class DecisionLogEvent extends ApplicationEvent {

    private final DecisionRecord decisionRecord;

    public DecisionLogEvent(Object source, DecisionRecord decisionRecord) {
        super(source);
        this.decisionRecord = decisionRecord;
    }

    public DecisionRecord getDecisionRecord() {
        return decisionRecord;
    }
}
