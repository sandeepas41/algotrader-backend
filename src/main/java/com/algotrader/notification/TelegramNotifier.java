package com.algotrader.notification;

import com.algotrader.domain.enums.AlertSeverity;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Sends messages via the Telegram Bot API with rate limiting.
 *
 * <p>Telegram imposes a 60-messages-per-minute limit per bot. This notifier uses
 * a semaphore-based rate limiter with a priority queue so that CRITICAL messages
 * are sent first when the rate limit is approached.
 *
 * <p>CRITICAL alerts bypass the rate limiter queue and send immediately to ensure
 * kill switch activations and session expiry are never delayed.
 */
@Component
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private static final String TELEGRAM_API_URL = "https://api.telegram.org/bot%s/sendMessage";

    private final TelegramConfig telegramConfig;
    private final RestTemplate restTemplate;

    // Rate limit: max 60 messages per minute (Telegram API limit)
    private final Semaphore rateLimiter = new Semaphore(60);

    // Queue for messages that can't be sent immediately, ordered by severity (CRITICAL first)
    private final BlockingQueue<TelegramMessage> messageQueue = new PriorityBlockingQueue<>(
            100, Comparator.comparingInt(m -> m.getSeverity().ordinal()));

    public TelegramNotifier(TelegramConfig telegramConfig) {
        this.telegramConfig = telegramConfig;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Send a message via Telegram.
     * CRITICAL messages bypass the rate limiter. Others are rate-limited to 60/min.
     */
    public void send(String message, AlertSeverity severity) {
        if (!telegramConfig.isEnabled()) {
            log.debug("Telegram notifications disabled");
            return;
        }

        TelegramMessage telegramMessage = TelegramMessage.builder()
                .text(message)
                .severity(severity)
                .timestamp(System.currentTimeMillis())
                .build();

        // CRITICAL alerts bypass the rate limiter — always send immediately
        if (severity == AlertSeverity.CRITICAL) {
            sendMessage(telegramMessage);
            return;
        }

        if (rateLimiter.tryAcquire()) {
            sendMessage(telegramMessage);
            scheduleRateLimiterRelease();
        } else {
            messageQueue.offer(telegramMessage);
            log.warn("Telegram rate limit reached, message queued. Queue size: {}", messageQueue.size());
        }
    }

    /**
     * Processes queued messages. Called every second to drain the queue
     * as rate limiter permits become available.
     */
    @Scheduled(fixedRate = 1000)
    public void processQueue() {
        while (!messageQueue.isEmpty() && rateLimiter.tryAcquire()) {
            TelegramMessage message = messageQueue.poll();
            if (message != null) {
                sendMessage(message);
                scheduleRateLimiterRelease();
            }
        }
    }

    private void sendMessage(TelegramMessage message) {
        try {
            String url = String.format(TELEGRAM_API_URL, telegramConfig.getBotToken());

            String severityEmoji =
                    switch (message.getSeverity()) {
                        case CRITICAL -> "\u26A0\uFE0F"; // warning sign
                        case WARNING -> "\u26A1"; // high voltage
                        case INFO -> "\u2139\uFE0F"; // info
                    };

            String formattedText = severityEmoji + " " + message.getText();

            Map<String, Object> payload = Map.of(
                    "chat_id",
                    telegramConfig.getChatId(),
                    "text",
                    formattedText,
                    "parse_mode",
                    "HTML",
                    "disable_web_page_preview",
                    true);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            restTemplate.postForEntity(url, request, String.class);
            log.debug("Telegram message sent successfully");

        } catch (Exception e) {
            log.error("Failed to send Telegram message: {}", e.getMessage());
        }
    }

    private void scheduleRateLimiterRelease() {
        // Release the permit after 1 second (60 per minute = 1 per second)
        CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(() -> rateLimiter.release());
    }

    /** Visible for testing — returns current queue size. */
    public int getQueueSize() {
        return messageQueue.size();
    }

    /** Visible for testing — returns available rate limiter permits. */
    public int getAvailablePermits() {
        return rateLimiter.availablePermits();
    }

    /** Visible for testing — drains all available permits so next send is queued. */
    public void drainPermits() {
        rateLimiter.drainPermits();
    }
}
