package com.algotrader.event;

import java.util.HashMap;
import java.util.Map;
import org.springframework.context.ApplicationEvent;

/**
 * Published for application lifecycle events (startup ready, shutdown, broker connect/disconnect).
 *
 * <p>System events enable coordinated startup and shutdown behavior:
 * <ul>
 *   <li>APPLICATION_READY — StartupRecoveryService checks for incomplete ExecutionJournals</li>
 *   <li>SHUTTING_DOWN — GracefulShutdownService persists all in-memory state to Redis</li>
 *   <li>BROKER_DISCONNECTED — StrategyEngine pauses all strategies</li>
 *   <li>BROKER_CONNECTED — StrategyEngine allows strategy resume</li>
 * </ul>
 */
public class SystemEvent extends ApplicationEvent {

    private final SystemEventType eventType;
    private final String message;
    private final Map<String, Object> details;

    public SystemEvent(Object source, SystemEventType eventType, String message) {
        super(source);
        this.eventType = eventType;
        this.message = message;
        this.details = new HashMap<>();
    }

    public SystemEvent(Object source, SystemEventType eventType, String message, Map<String, Object> details) {
        super(source);
        this.eventType = eventType;
        this.message = message;
        this.details = details != null ? new HashMap<>(details) : new HashMap<>();
    }

    public SystemEventType getEventType() {
        return eventType;
    }

    public String getMessage() {
        return message;
    }

    /**
     * Event-specific details. For example:
     * <ul>
     *   <li>APPLICATION_READY: {"startupDurationMs": 3500}</li>
     *   <li>BROKER_DISCONNECTED: {"reason": "WebSocket timeout", "lastHeartbeat": "..."}</li>
     * </ul>
     */
    public Map<String, Object> getDetails() {
        return details;
    }
}
