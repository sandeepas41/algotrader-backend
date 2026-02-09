package com.algotrader.event;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Application event published when a strategy morph completes.
 *
 * <p>Listeners:
 * <ul>
 *   <li>WebSocket handler -- pushes morph status to the frontend</li>
 *   <li>DecisionLogger -- records the morph with full context</li>
 *   <li>AlertService -- sends notification about morph execution</li>
 * </ul>
 */
@Getter
public class MorphEvent extends ApplicationEvent {

    private final String sourceStrategyId;
    private final List<String> newStrategyIds;
    private final LocalDateTime morphedAt;

    public MorphEvent(Object source, String sourceStrategyId, List<String> newStrategyIds) {
        super(source);
        this.sourceStrategyId = sourceStrategyId;
        this.newStrategyIds = newStrategyIds;
        this.morphedAt = LocalDateTime.now();
    }
}
