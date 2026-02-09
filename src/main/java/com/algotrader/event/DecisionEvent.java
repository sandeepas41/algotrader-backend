package com.algotrader.event;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a notable trading decision is made, for audit trail and debugging.
 *
 * <p>Decision events capture the "why" behind system actions: why an order was placed,
 * why a strategy entered/exited, why an adjustment was triggered, why a risk limit
 * was breached. They form the decision log that helps the trader understand and
 * review automated actions.
 *
 * <p>Examples of decisions logged:
 * <ul>
 *   <li>"Straddle entry triggered: NIFTY ATM IV=15.2 > threshold 14.0"</li>
 *   <li>"Order rejected by risk manager: daily loss limit would be exceeded"</li>
 *   <li>"IV solver fell back from Newton-Raphson to bisection for NIFTY24FEB22000CE"</li>
 *   <li>"Kill switch activated by user via dashboard"</li>
 * </ul>
 *
 * <p>Key listeners:
 * <ul>
 *   <li>DecisionLogService — persists to H2 for historical review</li>
 *   <li>WebSocketHandler — pushes to frontend decision log feed</li>
 * </ul>
 */
public class DecisionEvent extends ApplicationEvent {

    private final String category;
    private final String message;
    private final String strategyId;
    private final Map<String, Object> context;
    private final LocalDateTime occurredAt;

    /**
     * @param source     the component that made the decision
     * @param category   classification (e.g., "ENTRY", "EXIT", "ADJUSTMENT", "RISK", "ORDER", "SYSTEM")
     * @param message    human-readable description of the decision
     * @param strategyId the related strategy ID, or null for non-strategy decisions
     * @param context    additional structured data for the decision log
     */
    public DecisionEvent(
            Object source, String category, String message, String strategyId, Map<String, Object> context) {
        super(source);
        this.category = category;
        this.message = message;
        this.strategyId = strategyId;
        this.context = context != null ? new HashMap<>(context) : new HashMap<>();
        this.occurredAt = LocalDateTime.now();
    }

    public DecisionEvent(Object source, String category, String message) {
        this(source, category, message, null, null);
    }

    public String getCategory() {
        return category;
    }

    public String getMessage() {
        return message;
    }

    /**
     * The strategy this decision relates to. Null for system-wide decisions
     * (e.g., kill switch, session events).
     */
    public String getStrategyId() {
        return strategyId;
    }

    /**
     * Structured context for the decision. For example:
     * <ul>
     *   <li>ENTRY: {"iv": 15.2, "threshold": 14.0, "underlying": "NIFTY"}</li>
     *   <li>RISK: {"currentLoss": -50000, "limit": -40000}</li>
     * </ul>
     */
    public Map<String, Object> getContext() {
        return context;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
