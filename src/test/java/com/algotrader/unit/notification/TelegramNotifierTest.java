package com.algotrader.unit.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.AlertSeverity;
import com.algotrader.notification.TelegramConfig;
import com.algotrader.notification.TelegramNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TelegramNotifier (Task 20.1).
 *
 * <p>Verifies: disabled mode skips send, CRITICAL bypasses rate limiter,
 * rate limiter queues when exhausted.
 */
class TelegramNotifierTest {

    private TelegramConfig telegramConfig;
    private TelegramNotifier telegramNotifier;

    @BeforeEach
    void setUp() {
        telegramConfig = new TelegramConfig();
        telegramNotifier = new TelegramNotifier(telegramConfig);
    }

    @Test
    void send_disabled_doesNothing() {
        telegramConfig.setEnabled(false);

        // Should not throw or queue
        telegramNotifier.send("test message", AlertSeverity.INFO);

        assertThat(telegramNotifier.getQueueSize()).isEqualTo(0);
    }

    @Test
    void send_enabled_critical_bypassesRateLimiter() {
        telegramConfig.setEnabled(true);
        telegramConfig.setBotToken("test-token");
        telegramConfig.setChatId("12345");

        int permitsBefore = telegramNotifier.getAvailablePermits();

        // CRITICAL bypasses rate limiter — no permit consumed
        // (Will fail on HTTP call but that's expected in test; permit should not change)
        telegramNotifier.send("Critical alert", AlertSeverity.CRITICAL);

        // No permit consumed for CRITICAL (it bypasses the semaphore)
        assertThat(telegramNotifier.getAvailablePermits()).isEqualTo(permitsBefore);
        assertThat(telegramNotifier.getQueueSize()).isEqualTo(0);
    }

    @Test
    void send_enabled_info_consumesPermit() {
        telegramConfig.setEnabled(true);
        telegramConfig.setBotToken("test-token");
        telegramConfig.setChatId("12345");

        int permitsBefore = telegramNotifier.getAvailablePermits();

        // INFO consumes a rate limiter permit
        telegramNotifier.send("Info alert", AlertSeverity.INFO);

        // Permit consumed (even though HTTP call will fail)
        assertThat(telegramNotifier.getAvailablePermits()).isEqualTo(permitsBefore - 1);
    }

    @Test
    void rateLimiter_exhausted_queuesMessage() {
        telegramConfig.setEnabled(true);
        telegramConfig.setBotToken("test-token");
        telegramConfig.setChatId("12345");

        // Drain all 60 permits directly via drainPermits() to avoid async timing issues.
        // Each real send() schedules a 1-second delayed release, which can race with
        // subsequent sends if there are many. Draining directly is deterministic.
        telegramNotifier.drainPermits();

        assertThat(telegramNotifier.getAvailablePermits()).isEqualTo(0);

        // Next non-CRITICAL should be queued since no permits are available
        telegramNotifier.send("queued msg", AlertSeverity.INFO);

        assertThat(telegramNotifier.getQueueSize()).isEqualTo(1);
    }
}
