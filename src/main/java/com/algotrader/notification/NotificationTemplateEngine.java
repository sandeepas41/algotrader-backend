package com.algotrader.notification;

import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Renders alert-specific message templates for external notification channels.
 *
 * <p>Templates use Telegram HTML parse mode ({@code <b>bold</b>}) for structured,
 * readable messages on mobile screens. Each alert type has a dedicated template
 * with actionable context (strategy name, P&L, recommended action).
 */
@Component
public class NotificationTemplateEngine {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public String render(Alert alert) {
        return switch (alert.getType()) {
            case RISK -> renderRiskAlert(alert);
            case ORDER_FILL -> renderOrderFillAlert(alert);
            case ORDER_REJECTION -> renderOrderRejectionAlert(alert);
            case STRATEGY -> renderStrategyAlert(alert);
            case SESSION -> renderSessionAlert(alert);
            case RECONCILIATION -> renderReconciliationAlert(alert);
            case KILL_SWITCH -> renderKillSwitchAlert(alert);
            case SYSTEM -> renderDefaultAlert(alert);
        };
    }

    private String renderRiskAlert(Alert alert) {
        return String.format(
                "<b>RISK ALERT</b>\n" + "<b>Type:</b> %s\n" + "<b>Severity:</b> %s\n" + "<b>Message:</b> %s\n"
                        + "<b>Time:</b> %s",
                alert.getTitle(),
                alert.getSeverity(),
                alert.getMessage(),
                alert.getTimestamp().format(TIME_FORMAT));
    }

    private String renderOrderFillAlert(Alert alert) {
        return String.format(
                "<b>ORDER FILLED</b>\n%s\n<b>Time:</b> %s",
                alert.getMessage(), alert.getTimestamp().format(TIME_FORMAT));
    }

    private String renderOrderRejectionAlert(Alert alert) {
        return String.format(
                "<b>ORDER REJECTED</b>\n%s\n<b>Time:</b> %s",
                alert.getMessage(), alert.getTimestamp().format(TIME_FORMAT));
    }

    private String renderStrategyAlert(Alert alert) {
        return String.format(
                "<b>STRATEGY UPDATE</b>\n" + "<b>Event:</b> %s\n" + "<b>Details:</b> %s\n" + "<b>Time:</b> %s",
                alert.getTitle(), alert.getMessage(), alert.getTimestamp().format(TIME_FORMAT));
    }

    private String renderSessionAlert(Alert alert) {
        return String.format(
                "<b>SESSION ALERT</b>\n" + "<b>Status:</b> %s\n" + "<b>Details:</b> %s\n"
                        + "<b>Action Required:</b> Re-authenticate at Kite login\n" + "<b>Time:</b> %s",
                alert.getTitle(), alert.getMessage(), alert.getTimestamp().format(TIME_FORMAT));
    }

    private String renderReconciliationAlert(Alert alert) {
        return String.format(
                "<b>RECONCILIATION</b>\n%s\n<b>Time:</b> %s",
                alert.getMessage(), alert.getTimestamp().format(TIME_FORMAT));
    }

    private String renderKillSwitchAlert(Alert alert) {
        return String.format(
                "<b>KILL SWITCH ACTIVATED</b>\n%s\n<b>Action:</b> All orders cancelled, positions being closed\n"
                        + "<b>Time:</b> %s",
                alert.getMessage(), alert.getTimestamp().format(TIME_FORMAT));
    }

    private String renderDefaultAlert(Alert alert) {
        return String.format(
                "<b>%s</b>\n%s\n<b>Time:</b> %s",
                alert.getTitle(), alert.getMessage(), alert.getTimestamp().format(TIME_FORMAT));
    }
}
