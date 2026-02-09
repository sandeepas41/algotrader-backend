package com.algotrader.notification;

import com.algotrader.domain.enums.AlertSeverity;
import com.algotrader.domain.enums.AlertType;
import com.algotrader.domain.enums.NotificationChannel;
import com.algotrader.entity.NotificationPreferenceEntity;
import com.algotrader.repository.jpa.NotificationPreferenceJpaRepository;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Determines which notification channels to use for a given alert.
 *
 * <p>Default routing by severity:
 * <ul>
 *   <li>CRITICAL: All channels (WebSocket + Telegram + Email)</li>
 *   <li>WARNING: WebSocket + Telegram</li>
 *   <li>INFO: WebSocket only</li>
 * </ul>
 *
 * <p>User preferences stored in notification_preferences table override these defaults.
 * If a user explicitly disables an alert type, no channels are returned.
 */
@Component
public class AlertSeverityRouter {

    private static final Logger log = LoggerFactory.getLogger(AlertSeverityRouter.class);

    private static final Map<AlertSeverity, Set<NotificationChannel>> DEFAULT_ROUTING = Map.of(
            AlertSeverity.CRITICAL,
                    Set.of(NotificationChannel.WEBSOCKET, NotificationChannel.TELEGRAM, NotificationChannel.EMAIL),
            AlertSeverity.WARNING, Set.of(NotificationChannel.WEBSOCKET, NotificationChannel.TELEGRAM),
            AlertSeverity.INFO, Set.of(NotificationChannel.WEBSOCKET));

    private final NotificationPreferenceJpaRepository notificationPreferenceJpaRepository;

    public AlertSeverityRouter(NotificationPreferenceJpaRepository notificationPreferenceJpaRepository) {
        this.notificationPreferenceJpaRepository = notificationPreferenceJpaRepository;
    }

    /**
     * Resolves channels for a given alert type and severity.
     * User preferences override default severity-based routing.
     */
    public Set<NotificationChannel> resolveChannels(AlertType alertType, AlertSeverity severity) {
        Optional<NotificationPreferenceEntity> preference =
                notificationPreferenceJpaRepository.findByAlertType(alertType.name());

        if (preference.isPresent()) {
            NotificationPreferenceEntity pref = preference.get();
            if (!pref.isEnabled()) {
                log.debug("Alert type {} explicitly disabled by user preference", alertType);
                return Set.of();
            }
            return parseChannels(pref.getEnabledChannels());
        }

        // Fall back to severity-based default
        return DEFAULT_ROUTING.getOrDefault(severity, Set.of(NotificationChannel.WEBSOCKET));
    }

    /**
     * Parses comma-separated channel string (e.g., "WEBSOCKET,TELEGRAM") into a set.
     */
    private Set<NotificationChannel> parseChannels(String channelsCsv) {
        if (channelsCsv == null || channelsCsv.isBlank()) {
            return Set.of(NotificationChannel.WEBSOCKET);
        }
        return Arrays.stream(channelsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(NotificationChannel::valueOf)
                .collect(Collectors.toSet());
    }
}
