package com.algotrader.api.websocket;

import com.algotrader.domain.model.ConditionRule;
import com.algotrader.event.ConditionTriggeredEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * WebSocket handler that pushes condition trigger notifications to the frontend.
 *
 * <p>Listens for ConditionTriggeredEvent (published by ConditionEngine when a rule fires)
 * and sends two WebSocket messages:
 * <ul>
 *   <li>{@code /topic/conditions} -- dedicated topic for the condition monitoring UI</li>
 *   <li>{@code /topic/updates} -- global updates feed for the dashboard</li>
 * </ul>
 *
 * <p>Runs async to prevent blocking the ConditionEngine's evaluation thread.
 */
@Component
public class ConditionStreamHandler {

    private static final Logger log = LoggerFactory.getLogger(ConditionStreamHandler.class);

    private final SimpMessagingTemplate simpMessagingTemplate;

    public ConditionStreamHandler(SimpMessagingTemplate simpMessagingTemplate) {
        this.simpMessagingTemplate = simpMessagingTemplate;
    }

    @Async("eventExecutor")
    @EventListener
    public void onConditionTriggered(ConditionTriggeredEvent event) {
        ConditionRule rule = event.getConditionRule();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ruleId", rule.getId());
        payload.put("ruleName", rule.getName());
        payload.put("instrumentToken", rule.getInstrumentToken());
        payload.put("tradingSymbol", rule.getTradingSymbol());
        payload.put("indicatorType", rule.getIndicatorType().name());
        payload.put("indicatorValue", event.getIndicatorValue());
        payload.put("operator", rule.getOperator().name());
        payload.put("thresholdValue", rule.getThresholdValue());
        payload.put("actionType", rule.getActionType().name());
        payload.put("triggeredAt", event.getTriggeredAt().toString());

        WebSocketMessage conditionMsg = WebSocketMessage.of("CONDITION_TRIGGERED", payload);
        simpMessagingTemplate.convertAndSend("/topic/conditions", conditionMsg);

        WebSocketMessage dashboardMsg = WebSocketMessage.of("CONDITION", payload);
        simpMessagingTemplate.convertAndSend("/topic/updates", dashboardMsg);

        log.debug("Pushed condition trigger for rule {} to WebSocket", rule.getId());
    }
}
