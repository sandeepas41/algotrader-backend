package com.algotrader.repository.jpa;

import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.entity.StrategyEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA repository for the strategies table.
 * Supports querying strategies by status, underlying/expiry, and recently closed.
 * Active strategies (ARMED, ACTIVE) are loaded at startup for the strategy engine.
 */
@Repository
public interface StrategyJpaRepository extends JpaRepository<StrategyEntity, String> {

    List<StrategyEntity> findByStatus(StrategyStatus status);

    List<StrategyEntity> findByStatusIn(List<StrategyStatus> statuses);

    @Query("SELECT s FROM StrategyEntity s WHERE s.status IN ('ARMED', 'ACTIVE')")
    List<StrategyEntity> findActiveStrategies();

    List<StrategyEntity> findByUnderlyingAndExpiry(String underlying, LocalDate expiry);

    /** Finds strategies eligible for restoration on startup (everything except CLOSED/CLOSING). */
    @Query("SELECT s FROM StrategyEntity s WHERE s.status NOT IN ('CLOSED', 'CLOSING')")
    List<StrategyEntity> findRestorableStrategies();

    @Query("SELECT s FROM StrategyEntity s WHERE s.closedAt >= :since ORDER BY s.closedAt DESC")
    List<StrategyEntity> findRecentlyClosed(@Param("since") LocalDateTime since);

    /** Update only the status column — avoids re-encoding the JSON config through entity save. */
    @Modifying
    @Transactional
    @Query("UPDATE StrategyEntity s SET s.status = :status WHERE s.id = :id")
    void updateStatus(@Param("id") String id, @Param("status") StrategyStatus status);

    /** Update status and deployedAt timestamp without touching config. */
    @Modifying
    @Transactional
    @Query("UPDATE StrategyEntity s SET s.status = :status, s.deployedAt = :deployedAt WHERE s.id = :id")
    void updateStatusAndDeployedAt(
            @Param("id") String id,
            @Param("status") StrategyStatus status,
            @Param("deployedAt") LocalDateTime deployedAt);

    /** Update status and closedAt timestamp without touching config. */
    @Modifying
    @Transactional
    @Query("UPDATE StrategyEntity s SET s.status = :status, s.closedAt = :closedAt WHERE s.id = :id")
    void updateStatusAndClosedAt(
            @Param("id") String id, @Param("status") StrategyStatus status, @Param("closedAt") LocalDateTime closedAt);

    /** Update the config JSON column for runtime config changes (exit thresholds, etc.). */
    @Modifying
    @Transactional
    @Query("UPDATE StrategyEntity s SET s.config = :config WHERE s.id = :id")
    void updateConfig(@Param("id") String id, @Param("config") String config);
}
