package com.algotrader.unit.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.domain.enums.AlertSeverity;
import com.algotrader.domain.enums.AlertType;
import com.algotrader.domain.enums.NotificationChannel;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Strategy;
import com.algotrader.event.OrderEvent;
import com.algotrader.event.OrderEventType;
import com.algotrader.event.RiskEvent;
import com.algotrader.event.RiskEventType;
import com.algotrader.event.RiskLevel;
import com.algotrader.event.SessionEvent;
import com.algotrader.event.SessionEventType;
import com.algotrader.event.StrategyEvent;
import com.algotrader.event.StrategyEventType;
import com.algotrader.notification.Alert;
import com.algotrader.notification.AlertSeverityRouter;
import com.algotrader.notification.NotificationService;
import com.algotrader.notification.NotificationTemplateEngine;
import com.algotrader.notification.TelegramNotifier;
import com.algotrader.notification.WebSocketNotifier;
import com.algotrader.session.SessionState;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for NotificationService (Task 20.1).
 *
 * <p>Verifies: event-to-alert conversion, channel routing, async delivery,
 * CRITICAL risk events, order fill/rejection notifications, strategy and session events.
 */
class NotificationServiceTest {

    @Mock
    private AlertSeverityRouter alertSeverityRouter;

    @Mock
    private TelegramNotifier telegramNotifier;

    @Mock
    private WebSocketNotifier webSocketNotifier;

    @Mock
    private NotificationTemplateEngine notificationTemplateEngine;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        notificationService = new NotificationService(
                alertSeverityRouter, telegramNotifier, webSocketNotifier, notificationTemplateEngine);
    }

    @Test
    void notify_routesToWebSocket() {
        Alert alert = Alert.builder()
                .type(AlertType.ORDER_FILL)
                .severity(AlertSeverity.INFO)
                .title("Order Filled")
                .message("test")
                .build();

        when(alertSeverityRouter.resolveChannels(AlertType.ORDER_FILL, AlertSeverity.INFO))
                .thenReturn(Set.of(NotificationChannel.WEBSOCKET));
        when(notificationTemplateEngine.render(any())).thenReturn("formatted");

        notificationService.notify(alert);

        verify(webSocketNotifier).send(alert);
        verify(telegramNotifier, never()).send(any(), any());
    }

    @Test
    void notify_routesToTelegramAndWebSocket() {
        Alert alert = Alert.builder()
                .type(AlertType.ORDER_REJECTION)
                .severity(AlertSeverity.WARNING)
                .title("Order Rejected")
                .message("test")
                .build();

        when(alertSeverityRouter.resolveChannels(AlertType.ORDER_REJECTION, AlertSeverity.WARNING))
                .thenReturn(Set.of(NotificationChannel.WEBSOCKET, NotificationChannel.TELEGRAM));
        when(notificationTemplateEngine.render(any())).thenReturn("formatted msg");

        notificationService.notify(alert);

        verify(webSocketNotifier).send(alert);
        verify(telegramNotifier).send("formatted msg", AlertSeverity.WARNING);
    }

    @Test
    void notify_noChannels_doesNotSend() {
        Alert alert = Alert.builder()
                .type(AlertType.STRATEGY)
                .severity(AlertSeverity.INFO)
                .title("test")
                .message("test")
                .build();

        when(alertSeverityRouter.resolveChannels(AlertType.STRATEGY, AlertSeverity.INFO))
                .thenReturn(Set.of());

        notificationService.notify(alert);

        verify(webSocketNotifier, never()).send(any());
        verify(telegramNotifier, never()).send(any(), any());
    }

    @Test
    void onRiskEvent_critical_sendsAlert() {
        RiskEvent event =
                new RiskEvent(this, RiskEventType.KILL_SWITCH_TRIGGERED, RiskLevel.CRITICAL, "Kill switch activated");

        when(alertSeverityRouter.resolveChannels(AlertType.RISK, AlertSeverity.CRITICAL))
                .thenReturn(Set.of(NotificationChannel.WEBSOCKET, NotificationChannel.TELEGRAM));
        when(notificationTemplateEngine.render(any())).thenReturn("risk alert");

        notificationService.onRiskEvent(event);

        verify(webSocketNotifier).send(any());
        verify(telegramNotifier).send("risk alert", AlertSeverity.CRITICAL);
    }

    @Test
    void onOrderEvent_filled_sendsInfoAlert() {
        Order order = Order.builder()
                .brokerOrderId("ORD-123")
                .side(OrderSide.BUY)
                .tradingSymbol("NIFTY24JAN22000CE")
                .filledQuantity(75)
                .averageFillPrice(new BigDecimal("145.50"))
                .build();

        OrderEvent event = new OrderEvent(this, order, OrderEventType.FILLED);

        when(alertSeverityRouter.resolveChannels(AlertType.ORDER_FILL, AlertSeverity.INFO))
                .thenReturn(Set.of(NotificationChannel.WEBSOCKET));
        when(notificationTemplateEngine.render(any())).thenReturn("filled");

        notificationService.onOrderEvent(event);

        ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(webSocketNotifier).send(alertCaptor.capture());
        Alert sentAlert = alertCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(sentAlert.getType()).isEqualTo(AlertType.ORDER_FILL);
        org.assertj.core.api.Assertions.assertThat(sentAlert.getMessage()).contains("NIFTY24JAN22000CE");
    }

    @Test
    void onOrderEvent_rejected_sendsWarningAlert() {
        Order order = Order.builder()
                .tradingSymbol("NIFTY24JAN22000CE")
                .rejectionReason("Insufficient margin")
                .build();

        OrderEvent event = new OrderEvent(this, order, OrderEventType.REJECTED);

        when(alertSeverityRouter.resolveChannels(AlertType.ORDER_REJECTION, AlertSeverity.WARNING))
                .thenReturn(Set.of(NotificationChannel.WEBSOCKET));
        when(notificationTemplateEngine.render(any())).thenReturn("rejected");

        notificationService.onOrderEvent(event);

        ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(webSocketNotifier).send(alertCaptor.capture());
        Alert sentAlert = alertCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(sentAlert.getType()).isEqualTo(AlertType.ORDER_REJECTION);
        org.assertj.core.api.Assertions.assertThat(sentAlert.getMessage()).contains("Insufficient margin");
    }

    @Test
    void onOrderEvent_placed_doesNotSendAlert() {
        Order order = Order.builder().tradingSymbol("NIFTY24JAN22000CE").build();
        OrderEvent event = new OrderEvent(this, order, OrderEventType.PLACED);

        notificationService.onOrderEvent(event);

        verify(webSocketNotifier, never()).send(any());
    }

    @Test
    void onStrategyEvent_entryTriggered_sendsAlert() {
        Strategy strategy = Strategy.builder().name("My Straddle").build();
        StrategyEvent event = new StrategyEvent(this, strategy, StrategyEventType.ENTRY_TRIGGERED);

        when(alertSeverityRouter.resolveChannels(AlertType.STRATEGY, AlertSeverity.INFO))
                .thenReturn(Set.of(NotificationChannel.WEBSOCKET));
        when(notificationTemplateEngine.render(any())).thenReturn("strategy alert");

        notificationService.onStrategyEvent(event);

        verify(webSocketNotifier).send(any());
    }

    @Test
    void onStrategyEvent_armed_doesNotSendAlert() {
        Strategy strategy = Strategy.builder().name("My Straddle").build();
        StrategyEvent event = new StrategyEvent(this, strategy, StrategyEventType.ARMED);

        notificationService.onStrategyEvent(event);

        verify(webSocketNotifier, never()).send(any());
    }

    @Test
    void onSessionEvent_expired_sendsCriticalAlert() {
        SessionEvent event = new SessionEvent(
                this, SessionEventType.SESSION_EXPIRED, SessionState.ACTIVE, SessionState.EXPIRED, "Session expired");

        when(alertSeverityRouter.resolveChannels(AlertType.SESSION, AlertSeverity.CRITICAL))
                .thenReturn(Set.of(NotificationChannel.WEBSOCKET, NotificationChannel.TELEGRAM));
        when(notificationTemplateEngine.render(any())).thenReturn("session expired");

        notificationService.onSessionEvent(event);

        verify(webSocketNotifier).send(any());
        verify(telegramNotifier).send("session expired", AlertSeverity.CRITICAL);
    }

    @Test
    void onSessionEvent_validated_doesNotSendAlert() {
        SessionEvent event = new SessionEvent(
                this, SessionEventType.SESSION_VALIDATED, SessionState.ACTIVE, SessionState.ACTIVE, "OK");

        notificationService.onSessionEvent(event);

        verify(webSocketNotifier, never()).send(any());
    }
}
