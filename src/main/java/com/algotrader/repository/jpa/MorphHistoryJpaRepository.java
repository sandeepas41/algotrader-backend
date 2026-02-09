package com.algotrader.repository.jpa;

import com.algotrader.entity.MorphHistoryEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the morph_history table (strategy lineage).
 *
 * <p>Provides queries for tracing parent-child relationships across morphs,
 * enabling full lineage tree construction and cumulative P&L calculation.
 */
@Repository
public interface MorphHistoryJpaRepository extends JpaRepository<MorphHistoryEntity, Long> {

    List<MorphHistoryEntity> findByParentStrategyId(String parentStrategyId);

    Optional<MorphHistoryEntity> findByChildStrategyId(String childStrategyId);

    List<MorphHistoryEntity> findAllByOrderByMorphedAtDesc();
}
