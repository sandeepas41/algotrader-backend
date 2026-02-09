package com.algotrader.api.websocket;

import com.algotrader.domain.model.DecisionRecord;
import com.algotrader.event.DecisionLogEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Streams decision log entries to the frontend via WebSocket STOMP.
 *
 * <p>Listens to {@link DecisionLogEvent} published by the {@code DecisionLogger} and
 * broadcasts structured decision records to connected clients. Messages are sent to:
 * <ul>
 *   <li>{@code /topic/decisions} -- all decision logs (primary feed)</li>
 *   <li>{@code /topic/decisions/{source}} -- filtered by source (e.g., strategy_engine)</li>
 *   <li>{@code /topic/decisions/strategy/{id}} -- filtered by specific strategy</li>
 * </ul>
 *
 * <p>The decision record is mapped to a flat DTO (Map) to avoid leaking domain model
 * structure to the frontend. The message format is {@code { type: "DECISION", data: {...} }}.
 *
 * <p>Runs async on the eventExecutor to avoid blocking the DecisionLogger's persist path.
 */
@Component
public class DecisionStreamHandler {

    private static final Logger log = LoggerFactory.getLogger(DecisionStreamHandler.class);

    private final SimpMessagingTemplate simpMessagingTemplate;

    public DecisionStreamHandler(SimpMessagingTemplate simpMessagingTemplate) {
        this.simpMessagingTemplate = simpMessagingTemplate;
    }

    @Async("eventExecutor")
    @EventListener
    public void onDecisionLog(DecisionLogEvent decisionLogEvent) {
        DecisionRecord record = decisionLogEvent.getDecisionRecord();

        Map<String, Object> payload = toPayload(record);
        WebSocketMessage message = WebSocketMessage.of("DECISION", payload);

        try {
            // Broadcast to all connected clients
            simpMessagingTemplate.convertAndSend("/topic/decisions", message);

            // Send to source-specific topic for filtered views
            if (record.getSource() != null) {
                simpMessagingTemplate.convertAndSend(
                        "/topic/decisions/" + record.getSource().name().toLowerCase(), message);
            }

            // Send to strategy-specific topic if applicable
            if (record.getSourceId() != null && record.getSourceId().startsWith("STR-")) {
                simpMessagingTemplate.convertAndSend("/topic/decisions/strategy/" + record.getSourceId(), message);
            }
        } catch (Exception e) {
            log.error("Failed to stream decision log via WebSocket: {}", e.getMessage());
        }
    }

    private Map<String, Object> toPayload(DecisionRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", record.getTimestamp());
        payload.put("source", record.getSource() != null ? record.getSource().name() : null);
        payload.put("sourceId", record.getSourceId());
        payload.put(
                "decisionType",
                record.getDecisionType() != null ? record.getDecisionType().name() : null);
        payload.put("outcome", record.getOutcome() != null ? record.getOutcome().name() : null);
        payload.put("reasoning", record.getReasoning());
        payload.put(
                "severity", record.getSeverity() != null ? record.getSeverity().name() : null);
        payload.put("dataContext", record.getDataContext());
        return payload;
    }
}
