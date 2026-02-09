package com.algotrader.notification;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the Telegram Bot API integration.
 *
 * <p>Reads from application.properties/yml:
 * <pre>
 * notifications.telegram.enabled=false
 * notifications.telegram.bot-token=${TELEGRAM_BOT_TOKEN:}
 * notifications.telegram.chat-id=${TELEGRAM_CHAT_ID:}
 * notifications.telegram.max-messages-per-minute=60
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "notifications.telegram")
public class TelegramConfig {

    private boolean enabled = false;
    private String botToken;
    private String chatId;
    private int maxMessagesPerMinute = 60;
}
