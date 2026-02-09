package com.algotrader.repository.jpa;

import com.algotrader.domain.enums.ConditionRuleStatus;
import com.algotrader.entity.CompositeConditionRuleEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the composite_condition_rules table.
 *
 * <p>Provides data access for composite condition rules that combine multiple
 * individual rules with AND/OR logic. Active composites are evaluated on the
 * same scheduled interval as individual interval-mode rules.
 */
@Repository
public interface CompositeConditionRuleJpaRepository extends JpaRepository<CompositeConditionRuleEntity, Long> {

    List<CompositeConditionRuleEntity> findByStatus(ConditionRuleStatus status);
}
