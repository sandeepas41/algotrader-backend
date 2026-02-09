package com.algotrader.unit.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.AlertSeverity;
import com.algotrader.domain.enums.AlertType;
import com.algotrader.notification.Alert;
import com.algotrader.notification.NotificationTemplateEngine;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for NotificationTemplateEngine (Task 20.1).
 *
 * <p>Verifies: each alert type renders correctly with HTML formatting,
 * includes time, and has the appropriate heading.
 */
class NotificationTemplateEngineTest {

    private NotificationTemplateEngine notificationTemplateEngine;

    @BeforeEach
    void setUp() {
        notificationTemplateEngine = new NotificationTemplateEngine();
    }

    @Test
    void riskAlert_includesTypeAndSeverity() {
        Alert alert = Alert.builder()
                .type(AlertType.RISK)
                .severity(AlertSeverity.CRITICAL)
                .title("DAILY_LOSS_LIMIT_BREACH")
                .message("Daily loss -50000 exceeds limit -40000")
                .timestamp(LocalDateTime.of(2025, 1, 15, 14, 30, 45))
                .build();

        String result = notificationTemplateEngine.render(alert);

        assertThat(result).contains("<b>RISK ALERT</b>");
        assertThat(result).contains("DAILY_LOSS_LIMIT_BREACH");
        assertThat(result).contains("CRITICAL");
        assertThat(result).contains("14:30:45");
    }

    @Test
    void orderFillAlert_includesMessage() {
        Alert alert = Alert.builder()
                .type(AlertType.ORDER_FILL)
                .severity(AlertSeverity.INFO)
                .title("Order Filled")
                .message("Order ORD-123 filled: BUY NIFTY24JAN22000CE 75 @ 145.50")
                .timestamp(LocalDateTime.of(2025, 1, 15, 10, 0, 0))
                .build();

        String result = notificationTemplateEngine.render(alert);

        assertThat(result).contains("<b>ORDER FILLED</b>");
        assertThat(result).contains("NIFTY24JAN22000CE");
        assertThat(result).contains("10:00:00");
    }

    @Test
    void orderRejectionAlert_includesReason() {
        Alert alert = Alert.builder()
                .type(AlertType.ORDER_REJECTION)
                .severity(AlertSeverity.WARNING)
                .title("Order Rejected")
                .message("Order rejected: NIFTY24JAN22000CE - Insufficient margin")
                .timestamp(LocalDateTime.of(2025, 1, 15, 11, 0, 0))
                .build();

        String result = notificationTemplateEngine.render(alert);

        assertThat(result).contains("<b>ORDER REJECTED</b>");
        assertThat(result).contains("Insufficient margin");
    }

    @Test
    void strategyAlert_includesEventAndDetails() {
        Alert alert = Alert.builder()
                .type(AlertType.STRATEGY)
                .severity(AlertSeverity.INFO)
                .title("Strategy ENTRY_TRIGGERED")
                .message("Strategy My Straddle: ENTRY_TRIGGERED")
                .timestamp(LocalDateTime.of(2025, 1, 15, 9, 20, 0))
                .build();

        String result = notificationTemplateEngine.render(alert);

        assertThat(result).contains("<b>STRATEGY UPDATE</b>");
        assertThat(result).contains("ENTRY_TRIGGERED");
        assertThat(result).contains("My Straddle");
    }

    @Test
    void sessionAlert_includesReauthAction() {
        Alert alert = Alert.builder()
                .type(AlertType.SESSION)
                .severity(AlertSeverity.CRITICAL)
                .title("SESSION_EXPIRED")
                .message("Kite session expired after 3 failed health checks")
                .timestamp(LocalDateTime.of(2025, 1, 15, 15, 0, 0))
                .build();

        String result = notificationTemplateEngine.render(alert);

        assertThat(result).contains("<b>SESSION ALERT</b>");
        assertThat(result).contains("Re-authenticate at Kite login");
        assertThat(result).contains("15:00:00");
    }

    @Test
    void killSwitchAlert_includesActionMessage() {
        Alert alert = Alert.builder()
                .type(AlertType.KILL_SWITCH)
                .severity(AlertSeverity.CRITICAL)
                .title("Kill Switch")
                .message("Kill switch activated due to daily loss limit breach")
                .timestamp(LocalDateTime.of(2025, 1, 15, 14, 0, 0))
                .build();

        String result = notificationTemplateEngine.render(alert);

        assertThat(result).contains("<b>KILL SWITCH ACTIVATED</b>");
        assertThat(result).contains("All orders cancelled, positions being closed");
    }
}
