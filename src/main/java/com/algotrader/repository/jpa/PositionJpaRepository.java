package com.algotrader.repository.jpa;

import com.algotrader.entity.PositionEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the positions table.
 * H2 stores historical position snapshots. Real-time positions live in Redis.
 * Used for EOD reconciliation and historical analysis.
 */
@Repository
public interface PositionJpaRepository extends JpaRepository<PositionEntity, String> {

    List<PositionEntity> findByStrategyId(String strategyId);

    List<PositionEntity> findByTradingSymbol(String tradingSymbol);

    List<PositionEntity> findByClosedAtBetween(LocalDateTime from, LocalDateTime to);

    List<PositionEntity> findByClosedAtIsNull();
}
