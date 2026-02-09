package com.algotrader.mapper;

import com.algotrader.domain.model.AdjustmentAction;
import com.algotrader.domain.model.AdjustmentRule;
import com.algotrader.domain.model.AdjustmentTrigger;
import com.algotrader.entity.AdjustmentRuleEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * MapStruct mapper between AdjustmentRule domain model and AdjustmentRuleEntity.
 *
 * <p>Both trigger (AdjustmentTrigger) and action (AdjustmentAction) are domain objects
 * stored as JSON strings in the entity. This mapper handles the JSON conversions.
 */
@Mapper
public interface AdjustmentRuleMapper {

    @Mapping(source = "trigger", target = "triggerConfig", qualifiedByName = "triggerToJson")
    @Mapping(source = "action", target = "actionConfig", qualifiedByName = "actionToJson")
    AdjustmentRuleEntity toEntity(AdjustmentRule adjustmentRule);

    @Mapping(source = "triggerConfig", target = "trigger", qualifiedByName = "jsonToTrigger")
    @Mapping(source = "actionConfig", target = "action", qualifiedByName = "jsonToAction")
    AdjustmentRule toDomain(AdjustmentRuleEntity entity);

    List<AdjustmentRule> toDomainList(List<AdjustmentRuleEntity> entities);

    @Named("triggerToJson")
    default String triggerToJson(AdjustmentTrigger trigger) {
        return JsonHelper.toJson(trigger);
    }

    @Named("jsonToTrigger")
    default AdjustmentTrigger jsonToTrigger(String json) {
        return JsonHelper.fromJson(json, AdjustmentTrigger.class);
    }

    @Named("actionToJson")
    default String actionToJson(AdjustmentAction action) {
        return JsonHelper.toJson(action);
    }

    @Named("jsonToAction")
    default AdjustmentAction jsonToAction(String json) {
        return JsonHelper.fromJson(json, AdjustmentAction.class);
    }
}
