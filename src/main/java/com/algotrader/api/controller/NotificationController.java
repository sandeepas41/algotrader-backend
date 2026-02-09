package com.algotrader.api.controller;

import com.algotrader.domain.enums.AlertSeverity;
import com.algotrader.domain.enums.AlertType;
import com.algotrader.domain.model.NotificationPreference;
import com.algotrader.entity.NotificationPreferenceEntity;
import com.algotrader.mapper.NotificationPreferenceMapper;
import com.algotrader.notification.Alert;
import com.algotrader.notification.NotificationService;
import com.algotrader.repository.jpa.NotificationPreferenceJpaRepository;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for notification preferences and test endpoints.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/notifications/preferences} -- list all notification preferences</li>
 *   <li>{@code PUT /api/notifications/preferences} -- update a notification preference</li>
 *   <li>{@code GET /api/notifications/test/telegram} -- send a test Telegram message</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationPreferenceJpaRepository notificationPreferenceJpaRepository;
    private final NotificationPreferenceMapper notificationPreferenceMapper;
    private final NotificationService notificationService;

    public NotificationController(
            NotificationPreferenceJpaRepository notificationPreferenceJpaRepository,
            NotificationPreferenceMapper notificationPreferenceMapper,
            NotificationService notificationService) {
        this.notificationPreferenceJpaRepository = notificationPreferenceJpaRepository;
        this.notificationPreferenceMapper = notificationPreferenceMapper;
        this.notificationService = notificationService;
    }

    @GetMapping("/preferences")
    public List<NotificationPreference> getPreferences() {
        List<NotificationPreferenceEntity> entities = notificationPreferenceJpaRepository.findAll();
        return notificationPreferenceMapper.toDomainList(entities);
    }

    @PutMapping("/preferences")
    public NotificationPreference updatePreference(@RequestBody NotificationPreference preference) {
        NotificationPreferenceEntity entity = notificationPreferenceMapper.toEntity(preference);
        NotificationPreferenceEntity saved = notificationPreferenceJpaRepository.save(entity);
        return notificationPreferenceMapper.toDomain(saved);
    }

    @GetMapping("/test/telegram")
    public Map<String, String> testTelegram() {
        Alert testAlert = Alert.builder()
                .type(AlertType.SYSTEM)
                .severity(AlertSeverity.INFO)
                .title("Test Message")
                .message("This is a test notification from AlgoTrader Platform")
                .build();
        notificationService.notify(testAlert);
        return Map.of("message", "Test notification sent");
    }
}
