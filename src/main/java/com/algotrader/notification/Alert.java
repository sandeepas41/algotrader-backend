package com.algotrader.notification;

import com.algotrader.domain.enums.AlertSeverity;
import com.algotrader.domain.enums.AlertType;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * Represents an alert to be routed through the notification system.
 *
 * <p>Alerts are created by event listeners (risk, order, strategy, session events)
 * and routed to appropriate channels (WebSocket, Telegram, Email) based on their
 * severity and the user's notification preferences.
 */
@Data
@Builder
public class Alert {

    private AlertType type;
    private AlertSeverity severity;
    private String title;
    private String message;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}
