package com.algotrader.repository.jpa;

import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.entity.OrderEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the orders table.
 * H2 stores historical order records. Real-time active orders live in Redis.
 * Used for trade history queries, reporting, and EOD reconciliation.
 */
@Repository
public interface OrderJpaRepository extends JpaRepository<OrderEntity, String> {

    Optional<OrderEntity> findByBrokerOrderId(String brokerOrderId);

    List<OrderEntity> findByStrategyId(String strategyId);

    List<OrderEntity> findByStatus(OrderStatus status);

    List<OrderEntity> findByStatusIn(List<OrderStatus> statuses);

    @Query("SELECT o FROM OrderEntity o WHERE o.placedAt BETWEEN :from AND :to ORDER BY o.placedAt DESC")
    List<OrderEntity> findByDateRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(o) FROM OrderEntity o WHERE o.placedAt >= :startOfDay")
    int getDailyOrderCount(@Param("startOfDay") LocalDateTime startOfDay);

    List<OrderEntity> findByCorrelationId(String correlationId);

    List<OrderEntity> findByParentOrderId(String parentOrderId);
}
