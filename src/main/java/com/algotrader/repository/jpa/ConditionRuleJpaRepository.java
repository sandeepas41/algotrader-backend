package com.algotrader.repository.jpa;

import com.algotrader.domain.enums.ConditionRuleStatus;
import com.algotrader.entity.ConditionRuleEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the condition_rules table.
 *
 * <p>Provides data access for condition rules used by the ConditionEngine.
 * Active rules are loaded at startup and cached in-memory for O(1) tick-level
 * evaluation. The repository is queried again when rules are created, updated,
 * or deleted via the REST API to refresh the cache.
 */
@Repository
public interface ConditionRuleJpaRepository extends JpaRepository<ConditionRuleEntity, Long> {

    List<ConditionRuleEntity> findByStatus(ConditionRuleStatus status);

    List<ConditionRuleEntity> findByInstrumentToken(Long instrumentToken);

    List<ConditionRuleEntity> findByStatusAndInstrumentToken(ConditionRuleStatus status, Long instrumentToken);
}
