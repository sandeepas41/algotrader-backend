package com.algotrader.notification;

import com.algotrader.domain.enums.AlertSeverity;
import com.algotrader.domain.enums.AlertType;
import com.algotrader.domain.enums.NotificationChannel;
import com.algotrader.domain.model.Order;
import com.algotrader.event.OrderEvent;
import com.algotrader.event.OrderEventType;
import com.algotrader.event.RiskEvent;
import com.algotrader.event.RiskLevel;
import com.algotrader.event.SessionEvent;
import com.algotrader.event.SessionEventType;
import com.algotrader.event.StrategyEvent;
import com.algotrader.event.StrategyEventType;
import java.time.LocalDateTime;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Central notification service that routes alerts to appropriate channels.
 *
 * <p>Listens for application events (risk, order, strategy, session) and converts
 * them into {@link Alert} objects that are routed through the {@link AlertSeverityRouter}
 * to the correct channels (WebSocket, Telegram, Email).
 *
 * <p>All notification delivery is asynchronous via {@code @Async("eventExecutor")}
 * to avoid blocking the main trading thread. A failed Telegram delivery should
 * never delay an order fill event.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final AlertSeverityRouter alertSeverityRouter;
    private final TelegramNotifier telegramNotifier;
    private final WebSocketNotifier webSocketNotifier;
    private final NotificationTemplateEngine notificationTemplateEngine;

    public NotificationService(
            AlertSeverityRouter alertSeverityRouter,
            TelegramNotifier telegramNotifier,
            WebSocketNotifier webSocketNotifier,
            NotificationTemplateEngine notificationTemplateEngine) {
        this.alertSeverityRouter = alertSeverityRouter;
        this.telegramNotifier = telegramNotifier;
        this.webSocketNotifier = webSocketNotifier;
        this.notificationTemplateEngine = notificationTemplateEngine;
    }

    /**
     * Send a notification through resolved channels.
     */
    public void notify(Alert alert) {
        Set<NotificationChannel> channels = alertSeverityRouter.resolveChannels(alert.getType(), alert.getSeverity());

        if (channels.isEmpty()) {
            log.debug("No channels enabled for alert: {} ({})", alert.getType(), alert.getSeverity());
            return;
        }

        String formattedMessage = notificationTemplateEngine.render(alert);

        for (NotificationChannel channel : channels) {
            try {
                switch (channel) {
                    case WEBSOCKET -> webSocketNotifier.send(alert);
                    case TELEGRAM -> telegramNotifier.send(formattedMessage, alert.getSeverity());
                    case EMAIL -> log.debug("Email channel not yet implemented for: {}", alert.getTitle());
                    // #TODO: Implement EmailNotifier when Spring Mail is configured
                }
            } catch (Exception e) {
                log.error("Failed to send notification via {}: {}", channel, e.getMessage());
            }
        }
    }

    /**
     * Listen for risk events and convert to notifications.
     */
    @Async("eventExecutor")
    @EventListener
    @org.springframework.core.annotation.Order(15)
    public void onRiskEvent(RiskEvent event) {
        Alert alert = Alert.builder()
                .type(AlertType.RISK)
                .severity(mapRiskLevel(event.getLevel()))
                .title(event.getEventType().name())
                .message(event.getMessage())
                .timestamp(LocalDateTime.now())
                .build();

        notify(alert);
    }

    /**
     * Listen for order events and notify on fills and rejections.
     */
    @Async("eventExecutor")
    @EventListener
    @org.springframework.core.annotation.Order(15)
    public void onOrderEvent(OrderEvent event) {
        if (event.getEventType() == OrderEventType.FILLED) {
            Order order = event.getOrder();
            Alert alert = Alert.builder()
                    .type(AlertType.ORDER_FILL)
                    .severity(AlertSeverity.INFO)
                    .title("Order Filled")
                    .message(String.format(
                            "Order %s filled: %s %s %d @ %s",
                            order.getBrokerOrderId(),
                            order.getSide(),
                            order.getTradingSymbol(),
                            order.getFilledQuantity(),
                            order.getAverageFillPrice()))
                    .timestamp(LocalDateTime.now())
                    .build();
            notify(alert);
        }

        if (event.getEventType() == OrderEventType.REJECTED) {
            Order order = event.getOrder();
            Alert alert = Alert.builder()
                    .type(AlertType.ORDER_REJECTION)
                    .severity(AlertSeverity.WARNING)
                    .title("Order Rejected")
                    .message(String.format(
                            "Order rejected: %s - %s", order.getTradingSymbol(), order.getRejectionReason()))
                    .timestamp(LocalDateTime.now())
                    .build();
            notify(alert);
        }
    }

    /**
     * Listen for strategy events (entry/exit triggers).
     */
    @Async("eventExecutor")
    @EventListener
    @org.springframework.core.annotation.Order(15)
    public void onStrategyEvent(StrategyEvent event) {
        if (event.getEventType() == StrategyEventType.ENTRY_TRIGGERED
                || event.getEventType() == StrategyEventType.EXIT_TRIGGERED) {
            Alert alert = Alert.builder()
                    .type(AlertType.STRATEGY)
                    .severity(AlertSeverity.INFO)
                    .title("Strategy " + event.getEventType().name())
                    .message(
                            String.format("Strategy %s: %s", event.getStrategy().getName(), event.getEventType()))
                    .timestamp(LocalDateTime.now())
                    .build();
            notify(alert);
        }
    }

    /**
     * Listen for session events (expiry and invalidation are critical).
     */
    @Async("eventExecutor")
    @EventListener
    @org.springframework.core.annotation.Order(15)
    public void onSessionEvent(SessionEvent event) {
        if (event.getEventType() == SessionEventType.SESSION_EXPIRED
                || event.getEventType() == SessionEventType.SESSION_INVALIDATED) {
            Alert alert = Alert.builder()
                    .type(AlertType.SESSION)
                    .severity(AlertSeverity.CRITICAL)
                    .title("Session " + event.getEventType().name())
                    .message(event.getMessage())
                    .timestamp(LocalDateTime.now())
                    .build();
            notify(alert);
        }
    }

    private AlertSeverity mapRiskLevel(RiskLevel level) {
        return switch (level) {
            case INFO -> AlertSeverity.INFO;
            case WARNING -> AlertSeverity.WARNING;
            case CRITICAL -> AlertSeverity.CRITICAL;
        };
    }
}
