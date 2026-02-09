package com.algotrader.event;

import com.algotrader.session.SessionState;
import java.time.LocalDateTime;
import org.springframework.context.ApplicationEvent;

/**
 * Published when the Kite session lifecycle changes (created, validated, expiring, expired, etc.).
 *
 * <p>Session events are critical for coordinating system behavior around broker
 * connectivity. When the session expires, strategies must be paused and live
 * order placement must be blocked.
 *
 * <p>Key listeners:
 * <ul>
 *   <li>StrategyEngine — pauses all strategies on SESSION_EXPIRED, allows resume on SESSION_RECONNECTED</li>
 *   <li>WebSocketHandler — pushes session status to frontend for re-auth prompt</li>
 *   <li>AlertService — sends Telegram/email alerts on SESSION_EXPIRY_WARNING and SESSION_EXPIRED</li>
 *   <li>AuditService — logs all session lifecycle events</li>
 * </ul>
 */
public class SessionEvent extends ApplicationEvent {

    private final SessionEventType eventType;
    private final SessionState previousState;
    private final SessionState newState;
    private final String message;
    private final LocalDateTime occurredAt;

    public SessionEvent(
            Object source,
            SessionEventType eventType,
            SessionState previousState,
            SessionState newState,
            String message) {
        super(source);
        this.eventType = eventType;
        this.previousState = previousState;
        this.newState = newState;
        this.message = message;
        this.occurredAt = LocalDateTime.now();
    }

    public SessionEventType getEventType() {
        return eventType;
    }

    public SessionState getPreviousState() {
        return previousState;
    }

    public SessionState getNewState() {
        return newState;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
