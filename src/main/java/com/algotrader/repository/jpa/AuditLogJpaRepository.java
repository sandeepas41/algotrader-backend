package com.algotrader.repository.jpa;

import com.algotrader.entity.AuditLogEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the audit_logs table.
 * Supports querying audit trail by entity, event type, and time range.
 * Write-behind batching is handled by DataSyncService to avoid blocking trading operations.
 */
@Repository
public interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, Long> {

    List<AuditLogEntity> findByEntityTypeAndEntityId(String entityType, String entityId);

    List<AuditLogEntity> findByEventType(String eventType);

    @Query("SELECT a FROM AuditLogEntity a WHERE a.timestamp BETWEEN :from AND :to ORDER BY a.timestamp DESC")
    List<AuditLogEntity> findByDateRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(
            "SELECT a FROM AuditLogEntity a WHERE a.entityType = :entityType AND a.entityId = :entityId ORDER BY a.timestamp DESC")
    List<AuditLogEntity> findByEntityOrderByTimestampDesc(
            @Param("entityType") String entityType, @Param("entityId") String entityId);
}
