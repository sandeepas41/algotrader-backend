package com.algotrader.repository.jpa;

import com.algotrader.entity.DeadLetterEventEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the dead_letter_events table.
 * Used by the dead letter queue service to persist failed events and
 * by the admin UI + scheduled reprocessor to inspect and retry them.
 */
@Repository
public interface DeadLetterEventJpaRepository extends JpaRepository<DeadLetterEventEntity, Long> {

    List<DeadLetterEventEntity> findByStatus(String status);

    List<DeadLetterEventEntity> findByEventType(String eventType);

    List<DeadLetterEventEntity> findByEventTypeAndStatus(String eventType, String status);
}
