package com.algotrader.unit.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.algotrader.domain.enums.AlertSeverity;
import com.algotrader.domain.enums.AlertType;
import com.algotrader.domain.enums.NotificationChannel;
import com.algotrader.entity.NotificationPreferenceEntity;
import com.algotrader.notification.AlertSeverityRouter;
import com.algotrader.repository.jpa.NotificationPreferenceJpaRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for AlertSeverityRouter (Task 20.1).
 *
 * <p>Verifies: default severity routing, user preference overrides,
 * disabled alert types, and fallback behavior.
 */
class AlertSeverityRouterTest {

    @Mock
    private NotificationPreferenceJpaRepository notificationPreferenceJpaRepository;

    private AlertSeverityRouter alertSeverityRouter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        alertSeverityRouter = new AlertSeverityRouter(notificationPreferenceJpaRepository);
    }

    @Test
    void critical_defaultRouting_allChannels() {
        when(notificationPreferenceJpaRepository.findByAlertType("RISK")).thenReturn(Optional.empty());

        Set<NotificationChannel> channels = alertSeverityRouter.resolveChannels(AlertType.RISK, AlertSeverity.CRITICAL);

        assertThat(channels)
                .containsExactlyInAnyOrder(
                        NotificationChannel.WEBSOCKET, NotificationChannel.TELEGRAM, NotificationChannel.EMAIL);
    }

    @Test
    void warning_defaultRouting_websocketAndTelegram() {
        when(notificationPreferenceJpaRepository.findByAlertType("ORDER_REJECTION"))
                .thenReturn(Optional.empty());

        Set<NotificationChannel> channels =
                alertSeverityRouter.resolveChannels(AlertType.ORDER_REJECTION, AlertSeverity.WARNING);

        assertThat(channels).containsExactlyInAnyOrder(NotificationChannel.WEBSOCKET, NotificationChannel.TELEGRAM);
    }

    @Test
    void info_defaultRouting_websocketOnly() {
        when(notificationPreferenceJpaRepository.findByAlertType("ORDER_FILL")).thenReturn(Optional.empty());

        Set<NotificationChannel> channels =
                alertSeverityRouter.resolveChannels(AlertType.ORDER_FILL, AlertSeverity.INFO);

        assertThat(channels).containsExactly(NotificationChannel.WEBSOCKET);
    }

    @Test
    void userPreference_overridesDefault() {
        NotificationPreferenceEntity pref = NotificationPreferenceEntity.builder()
                .alertType("ORDER_FILL")
                .enabledChannels("WEBSOCKET,TELEGRAM")
                .enabled(true)
                .build();
        when(notificationPreferenceJpaRepository.findByAlertType("ORDER_FILL")).thenReturn(Optional.of(pref));

        Set<NotificationChannel> channels =
                alertSeverityRouter.resolveChannels(AlertType.ORDER_FILL, AlertSeverity.INFO);

        // User enabled Telegram for order fills (not default)
        assertThat(channels).containsExactlyInAnyOrder(NotificationChannel.WEBSOCKET, NotificationChannel.TELEGRAM);
    }

    @Test
    void userPreference_disabled_returnsEmpty() {
        NotificationPreferenceEntity pref = NotificationPreferenceEntity.builder()
                .alertType("STRATEGY")
                .enabledChannels("WEBSOCKET")
                .enabled(false)
                .build();
        when(notificationPreferenceJpaRepository.findByAlertType("STRATEGY")).thenReturn(Optional.of(pref));

        Set<NotificationChannel> channels = alertSeverityRouter.resolveChannels(AlertType.STRATEGY, AlertSeverity.INFO);

        assertThat(channels).isEmpty();
    }
}
