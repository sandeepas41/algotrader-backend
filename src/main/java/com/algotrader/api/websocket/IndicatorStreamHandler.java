package com.algotrader.api.websocket;

import com.algotrader.event.IndicatorUpdateEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Streams indicator updates to the frontend via STOMP WebSocket.
 *
 * <p>When a bar completes and indicators are recalculated, this handler pushes
 * the updated values to two destinations:
 * <ul>
 *   <li>{@code /topic/indicators/{instrumentToken}} -- per-instrument topic for
 *       subscribers who want updates for a specific instrument</li>
 *   <li>{@code /topic/updates} -- general updates topic with type "INDICATOR"
 *       for the dashboard indicator strip</li>
 * </ul>
 *
 * <p>Runs async on the eventExecutor to avoid blocking indicator calculation.
 */
@Component
public class IndicatorStreamHandler {

    private static final Logger log = LoggerFactory.getLogger(IndicatorStreamHandler.class);

    private final SimpMessagingTemplate simpMessagingTemplate;

    public IndicatorStreamHandler(SimpMessagingTemplate simpMessagingTemplate) {
        this.simpMessagingTemplate = simpMessagingTemplate;
    }

    @Async("eventExecutor")
    @EventListener
    public void onIndicatorUpdate(IndicatorUpdateEvent indicatorUpdateEvent) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("instrumentToken", indicatorUpdateEvent.getInstrumentToken());
            payload.put("tradingSymbol", indicatorUpdateEvent.getTradingSymbol());
            payload.put("indicators", indicatorUpdateEvent.getIndicators());
            payload.put("timestamp", indicatorUpdateEvent.getUpdateTime().toString());

            // Per-instrument topic for detail views
            WebSocketMessage instrumentMsg = WebSocketMessage.of("INDICATOR_UPDATE", payload);
            simpMessagingTemplate.convertAndSend(
                    "/topic/indicators/" + indicatorUpdateEvent.getInstrumentToken(), instrumentMsg);

            // General updates topic for dashboard
            WebSocketMessage dashboardMsg = WebSocketMessage.of("INDICATOR", payload);
            simpMessagingTemplate.convertAndSend("/topic/updates", dashboardMsg);
        } catch (Exception e) {
            log.error(
                    "Failed to stream indicator update for token {}: {}",
                    indicatorUpdateEvent.getInstrumentToken(),
                    e.getMessage());
        }
    }
}
