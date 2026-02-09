package com.algotrader.domain.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-alert-type notification settings.
 *
 * <p>Controls which notification channels (WebSocket, Telegram, Email) are enabled
 * for each alert type. For example, "KILL_SWITCH_TRIGGERED" might enable all channels,
 * while "DAILY_PNL_SUMMARY" might only enable Email.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreference {

    private Long id;

    /** Alert type identifier (e.g., "ORDER_FILLED", "RISK_BREACH", "SESSION_EXPIRED"). */
    private String alertType;

    /** Comma-separated enabled channels (e.g., "WEBSOCKET,TELEGRAM"). */
    private String enabledChannels;

    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
