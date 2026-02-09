package com.algotrader.repository.jpa;

import com.algotrader.domain.enums.StrategyRunStatus;
import com.algotrader.entity.StrategyRunEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the strategy_runs table.
 * Supports querying run history by strategy, status, and date range.
 * Used by the trade journal, strategy detail page, and P&L reporting.
 */
@Repository
public interface StrategyRunJpaRepository extends JpaRepository<StrategyRunEntity, String> {

    List<StrategyRunEntity> findByStrategyId(String strategyId);

    List<StrategyRunEntity> findByStrategyIdAndStatus(String strategyId, StrategyRunStatus status);

    List<StrategyRunEntity> findByStatus(StrategyRunStatus status);

    @Query("SELECT r FROM StrategyRunEntity r WHERE r.entryTime BETWEEN :from AND :to ORDER BY r.entryTime DESC")
    List<StrategyRunEntity> findByDateRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT r FROM StrategyRunEntity r WHERE r.strategyId = :strategyId ORDER BY r.entryTime DESC")
    List<StrategyRunEntity> findByStrategyIdOrderByEntryTimeDesc(@Param("strategyId") String strategyId);
}
