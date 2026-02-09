package com.algotrader.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Wraps the existing WebSocket STOMP push mechanism to deliver alerts in-app.
 *
 * <p>Sends alerts to {@code /topic/alerts} which the frontend NotificationCenter
 * subscribes to. This is the default channel for all alert severities.
 */
@Component
public class WebSocketNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebSocketNotifier.class);

    private final SimpMessagingTemplate simpMessagingTemplate;

    public WebSocketNotifier(SimpMessagingTemplate simpMessagingTemplate) {
        this.simpMessagingTemplate = simpMessagingTemplate;
    }

    public void send(Alert alert) {
        try {
            simpMessagingTemplate.convertAndSend("/topic/alerts", alert);
            log.debug("WebSocket alert sent: {}", alert.getTitle());
        } catch (Exception e) {
            log.error("Failed to send WebSocket alert: {}", e.getMessage());
        }
    }
}
