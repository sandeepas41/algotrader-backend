package com.algotrader.repository.jpa;

import com.algotrader.entity.StrategyLegEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the strategy_legs table.
 * Loads all legs for a given strategy. Also supports queries by positionId for
 * allocation tracking (finding which strategies reference a given position).
 */
@Repository
public interface StrategyLegJpaRepository extends JpaRepository<StrategyLegEntity, String> {

    List<StrategyLegEntity> findByStrategyId(String strategyId);

    List<StrategyLegEntity> findByPositionId(String positionId);

    /** Batch query: find all legs linked to any of the given position IDs. */
    List<StrategyLegEntity> findByPositionIdIn(Collection<String> positionIds);

    /** Find all legs that have a non-null positionId (i.e., currently linked to a position). */
    List<StrategyLegEntity> findByPositionIdIsNotNull();
}
