package com.algotrader.mapper;

import com.algotrader.domain.model.StrategyLeg;
import com.algotrader.domain.model.StrikeSelection;
import com.algotrader.entity.StrategyLegEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * MapStruct mapper between StrategyLeg domain model and StrategyLegEntity.
 *
 * <p>StrikeSelection is a domain object stored as JSON string in the entity.
 * This mapper handles JSON serialization/deserialization via JsonHelper.
 */
@Mapper
public interface StrategyLegMapper {

    @Mapping(source = "strikeSelection", target = "strikeSelection", qualifiedByName = "strikeSelectionToJson")
    StrategyLegEntity toEntity(StrategyLeg strategyLeg);

    @Mapping(source = "strikeSelection", target = "strikeSelection", qualifiedByName = "jsonToStrikeSelection")
    StrategyLeg toDomain(StrategyLegEntity entity);

    List<StrategyLeg> toDomainList(List<StrategyLegEntity> entities);

    @Named("strikeSelectionToJson")
    default String strikeSelectionToJson(StrikeSelection strikeSelection) {
        return JsonHelper.toJson(strikeSelection);
    }

    @Named("jsonToStrikeSelection")
    default StrikeSelection jsonToStrikeSelection(String json) {
        return JsonHelper.fromJson(json, StrikeSelection.class);
    }
}
