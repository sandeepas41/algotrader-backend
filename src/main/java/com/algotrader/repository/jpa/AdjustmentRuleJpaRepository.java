package com.algotrader.repository.jpa;

import com.algotrader.entity.AdjustmentRuleEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the adjustment_rules table.
 * Rules are loaded with their parent strategy and evaluated in priority order
 * by the adjustment engine during active trading.
 */
@Repository
public interface AdjustmentRuleJpaRepository extends JpaRepository<AdjustmentRuleEntity, Long> {

    List<AdjustmentRuleEntity> findByStrategyId(String strategyId);

    List<AdjustmentRuleEntity> findByStrategyIdAndEnabledTrue(String strategyId);

    List<AdjustmentRuleEntity> findByStrategyIdOrderByPriorityAsc(String strategyId);
}
