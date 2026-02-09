package com.algotrader.repository.jpa;

import com.algotrader.entity.ConditionTriggerHistoryEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the condition_trigger_history table.
 *
 * <p>Provides access to the audit trail of condition rule triggers.
 * History records are created by the ConditionEngine when a rule fires
 * and queried by the REST API for the trigger history endpoint.
 */
@Repository
public interface ConditionTriggerHistoryJpaRepository extends JpaRepository<ConditionTriggerHistoryEntity, Long> {

    List<ConditionTriggerHistoryEntity> findByRuleIdOrderByTriggeredAtDesc(Long ruleId);

    List<ConditionTriggerHistoryEntity> findTop50ByOrderByTriggeredAtDesc();
}
