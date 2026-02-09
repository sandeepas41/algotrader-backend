package com.algotrader.repository.jpa;

import com.algotrader.entity.StrategyLegEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the strategy_legs table.
 * Loads all legs for a given strategy. Legs are always fetched together with their strategy
 * by the mapper/service layer — never queried independently in normal operation.
 */
@Repository
public interface StrategyLegJpaRepository extends JpaRepository<StrategyLegEntity, String> {

    List<StrategyLegEntity> findByStrategyId(String strategyId);

    List<StrategyLegEntity> findByPositionId(String positionId);
}
