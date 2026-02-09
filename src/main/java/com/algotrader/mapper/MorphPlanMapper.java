package com.algotrader.mapper;

import com.algotrader.domain.model.MorphPlan;
import com.algotrader.entity.MorphPlanEntity;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper between MorphPlan domain model and MorphPlanEntity.
 *
 * <p>Handles conversion for morph plan WAL entries. All fields have matching
 * names so no explicit @Mapping annotations are needed.
 */
@Mapper
public interface MorphPlanMapper {

    MorphPlanEntity toEntity(MorphPlan morphPlan);

    MorphPlan toDomain(MorphPlanEntity entity);

    List<MorphPlan> toDomainList(List<MorphPlanEntity> entities);
}
