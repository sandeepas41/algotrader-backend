package com.algotrader.repository.jpa;

import com.algotrader.domain.enums.MorphPlanStatus;
import com.algotrader.entity.MorphPlanEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the morph_plans table (WAL entries).
 *
 * <p>Provides queries for morph plan management, crash recovery (finding
 * EXECUTING plans on startup), and history browsing.
 */
@Repository
public interface MorphPlanJpaRepository extends JpaRepository<MorphPlanEntity, Long> {

    List<MorphPlanEntity> findByStatus(MorphPlanStatus status);

    List<MorphPlanEntity> findBySourceStrategyId(String sourceStrategyId);

    List<MorphPlanEntity> findAllByOrderByCreatedAtDesc();
}
