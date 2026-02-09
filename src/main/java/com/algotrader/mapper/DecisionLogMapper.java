package com.algotrader.mapper;

import com.algotrader.domain.model.DecisionRecord;
import com.algotrader.entity.DecisionLogEntity;
import java.util.List;
import java.util.Map;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * MapStruct mapper between DecisionRecord domain model and DecisionLogEntity.
 *
 * <p>The dataContext field is a Map in the domain model but stored as a JSON string
 * in the entity. This mapper handles the JSON conversion via {@link JsonHelper}.
 */
@Mapper
public interface DecisionLogMapper {

    @Mapping(source = "dataContext", target = "dataContext", qualifiedByName = "mapToJson")
    DecisionLogEntity toEntity(DecisionRecord decisionRecord);

    @Mapping(source = "dataContext", target = "dataContext", qualifiedByName = "jsonToMap")
    DecisionRecord toDomain(DecisionLogEntity entity);

    List<DecisionRecord> toDomainList(List<DecisionLogEntity> entities);

    List<DecisionLogEntity> toEntityList(List<DecisionRecord> records);

    @Named("mapToJson")
    default String mapToJson(Map<String, Object> dataContext) {
        return JsonHelper.toJson(dataContext);
    }

    @SuppressWarnings("unchecked")
    @Named("jsonToMap")
    default Map<String, Object> jsonToMap(String json) {
        return JsonHelper.fromJson(json, Map.class);
    }
}
