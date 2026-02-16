package com.algotrader.repository.jpa;

import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.entity.StrategyEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
