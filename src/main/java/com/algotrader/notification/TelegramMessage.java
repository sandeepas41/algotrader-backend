package com.algotrader.notification;

import com.algotrader.domain.enums.AlertSeverity;
import lombok.Builder;
import lombok.Data;

/**
 * Internal message queued for Telegram delivery.
 *
 * <p>Severity is used by the priority queue in TelegramNotifier so that
 * CRITICAL messages are sent first when the rate limiter is saturated.
 */
@Data
@Builder
public class TelegramMessage {

    private String text;
    private AlertSeverity severity;
    private long timestamp;
}
