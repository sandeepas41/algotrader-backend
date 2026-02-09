package com.algotrader.event;

import java.util.HashMap;
import java.util.Map;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a risk condition is detected by the RiskManager or KillSwitch.
 *
 * <p>Risk events carry the type of risk condition, its severity level, a human-readable
 * message, and a details map for condition-specific data (e.g., current P&L value,
 * configured limit, margin utilization percentage).
 *
 * <p>Key listeners:
 * <ul>
 *   <li>AlertService — sends risk alerts via Telegram/email</li>
 *   <li>StrategyEngine — may pause strategies on CRITICAL events</li>
 *   <li>OrderService — may block new orders on limit breaches</li>
 *   <li>WebSocketHandler — pushes risk alerts to frontend</li>
 * </ul>
 */
public class RiskEvent extends ApplicationEvent {

    private final RiskEventType eventType;
    private final RiskLevel level;
    private final String message;
    private final Map<String, Object> details;

    public RiskEvent(Object source, RiskEventType eventType, RiskLevel level, String message) {
        super(source);
        this.eventType = eventType;
        this.level = level;
        this.message = message;
        this.details = new HashMap<>();
    }

    public RiskEvent(
            Object source, RiskEventType eventType, RiskLevel level, String message, Map<String, Object> details) {
        super(source);
        this.eventType = eventType;
        this.level = level;
        this.message = message;
        this.details = details != null ? new HashMap<>(details) : new HashMap<>();
    }

    public RiskEventType getEventType() {
        return eventType;
    }

    public RiskLevel getLevel() {
        return level;
    }

    public String getMessage() {
        return message;
    }

    /**
     * Condition-specific details. For example:
     * <ul>
     *   <li>DAILY_LOSS_LIMIT_BREACH: {"currentLoss": -50000, "limit": -40000}</li>
     *   <li>MARGIN_UTILIZATION_HIGH: {"utilization": 85.5, "threshold": 80}</li>
     * </ul>
     */
    public Map<String, Object> getDetails() {
        return details;
    }
}
