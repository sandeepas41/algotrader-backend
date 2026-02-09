package com.algotrader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the notification_preferences table.
 * Per-alert-type settings controlling which notification channels are enabled.
 * For example, KILL_SWITCH_TRIGGERED enables all channels, while DAILY_PNL_SUMMARY
 * may only enable EMAIL.
 */
@Entity
@Table(name = "notification_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationPreferenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Alert type identifier (e.g., "ORDER_FILLED", "RISK_BREACH", "SESSION_EXPIRED"). */
    @Column(name = "alert_type", length = 50)
    private String alertType;

    /** Comma-separated enabled channels (e.g., "WEBSOCKET,TELEGRAM"). */
    @Column(name = "enabled_channels", length = 100)
    private String enabledChannels;

    private boolean enabled;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
