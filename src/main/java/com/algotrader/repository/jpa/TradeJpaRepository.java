package com.algotrader.repository.jpa;

import com.algotrader.entity.TradeEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the trades table.
 * Primary store for executed trade history with itemized charge breakdowns.
 * Supports date-range queries, daily P&L aggregation, and per-symbol summaries.
 */
@Repository
public interface TradeJpaRepository extends JpaRepository<TradeEntity, String> {

    List<TradeEntity> findByOrderId(String orderId);

    List<TradeEntity> findByStrategyId(String strategyId);

    @Query("SELECT t FROM TradeEntity t WHERE t.executedAt BETWEEN :from AND :to ORDER BY t.executedAt DESC")
    List<TradeEntity> findByDateRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(SUM(t.pnl), 0) FROM TradeEntity t WHERE t.executedAt >= :startOfDay")
    BigDecimal getDailyRealizedPnl(@Param("startOfDay") LocalDateTime startOfDay);

    @Query("SELECT COUNT(t) FROM TradeEntity t WHERE t.executedAt >= :startOfDay")
    int getDailyTradeCount(@Param("startOfDay") LocalDateTime startOfDay);

    List<TradeEntity> findByTradingSymbolAndExecutedAtBetween(
            String tradingSymbol, LocalDateTime from, LocalDateTime to);
}
