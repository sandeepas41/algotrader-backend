package com.algotrader.repository.jpa;

import com.algotrader.entity.DailyPnlEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the daily_pnl table.
 * One row per trading day, populated by EOD reconciliation.
 * Used by the dashboard for P&L charts and performance metrics.
 */
@Repository
public interface DailyPnlJpaRepository extends JpaRepository<DailyPnlEntity, Long> {

    Optional<DailyPnlEntity> findByDate(LocalDate date);

    @Query("SELECT d FROM DailyPnlEntity d WHERE d.date BETWEEN :from AND :to ORDER BY d.date ASC")
    List<DailyPnlEntity> findByDateRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT d FROM DailyPnlEntity d ORDER BY d.date DESC")
    List<DailyPnlEntity> findRecentDays(@Param("limit") int limit);
}
