package com.algotrader.repository.jpa;

import com.algotrader.entity.NotificationPreferenceEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the notification_preferences table.
 * Loaded at startup and cached. Settings page updates preferences via this repository.
 */
@Repository
public interface NotificationPreferenceJpaRepository extends JpaRepository<NotificationPreferenceEntity, Long> {

    Optional<NotificationPreferenceEntity> findByAlertType(String alertType);

    List<NotificationPreferenceEntity> findByEnabledTrue();
}
