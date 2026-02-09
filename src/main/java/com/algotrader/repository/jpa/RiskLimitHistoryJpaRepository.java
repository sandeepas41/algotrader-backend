package com.algotrader.repository.jpa;

import com.algotrader.entity.RiskLimitHistoryEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the risk_limit_history table.
 * Provides audit trail for risk limit changes, queryable by limit type and date range.
 */
@Repository
public interface RiskLimitHistoryJpaRepository extends JpaRepository<RiskLimitHistoryEntity, Long> {

    List<RiskLimitHistoryEntity> findByLimitType(String limitType);

    @Query("SELECT r FROM RiskLimitHistoryEntity r WHERE r.timestamp BETWEEN :from AND :to ORDER BY r.timestamp DESC")
    List<RiskLimitHistoryEntity> findByDateRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT r FROM RiskLimitHistoryEntity r WHERE r.limitType = :limitType ORDER BY r.timestamp DESC")
    List<RiskLimitHistoryEntity> findByLimitTypeOrderByTimestampDesc(@Param("limitType") String limitType);
}
