package com.algotrader.mapper;

import com.algotrader.domain.model.CompositeConditionRule;
import com.algotrader.domain.model.ConditionRule;
import com.algotrader.domain.model.ConditionTriggerHistory;
import com.algotrader.entity.CompositeConditionRuleEntity;
import com.algotrader.entity.ConditionRuleEntity;
import com.algotrader.entity.ConditionTriggerHistoryEntity;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for condition engine domain models and JPA entities.
 *
 * <p>Provides bidirectional mapping between:
 * <ul>
 *   <li>{@link ConditionRule} domain model <-> {@link ConditionRuleEntity}</li>
 *   <li>{@link CompositeConditionRule} domain model <-> {@link CompositeConditionRuleEntity}</li>
 *   <li>{@link ConditionTriggerHistory} domain model <-> {@link ConditionTriggerHistoryEntity}</li>
 * </ul>
 *
 * <p>Field names are identical across domain and entity layers, so no explicit
 * {@code @Mapping} annotations are needed.
 */
@Mapper
public interface ConditionRuleMapper {

    // ConditionRule
    ConditionRule toDomain(ConditionRuleEntity entity);

    ConditionRuleEntity toEntity(ConditionRule domain);

    List<ConditionRule> toDomainList(List<ConditionRuleEntity> entities);

    // CompositeConditionRule
    CompositeConditionRule toDomain(CompositeConditionRuleEntity entity);

    CompositeConditionRuleEntity toEntity(CompositeConditionRule domain);

    List<CompositeConditionRule> toCompositeDomainList(List<CompositeConditionRuleEntity> entities);

    // ConditionTriggerHistory
    ConditionTriggerHistory toDomain(ConditionTriggerHistoryEntity entity);

    ConditionTriggerHistoryEntity toEntity(ConditionTriggerHistory domain);

    List<ConditionTriggerHistory> toHistoryDomainList(List<ConditionTriggerHistoryEntity> entities);
}
