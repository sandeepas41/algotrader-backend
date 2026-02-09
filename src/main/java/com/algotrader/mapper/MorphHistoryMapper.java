package com.algotrader.mapper;

import com.algotrader.domain.model.StrategyLineage;
import com.algotrader.entity.MorphHistoryEntity;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper between StrategyLineage domain model and MorphHistoryEntity.
 *
 * <p>Note the different naming: the domain model is called StrategyLineage (concept-oriented),
 * while the entity/table is called MorphHistory (table-oriented). Field names are identical
 * so no explicit @Mapping is needed.
 */
@Mapper
public interface MorphHistoryMapper {

    MorphHistoryEntity toEntity(StrategyLineage strategyLineage);

    StrategyLineage toDomain(MorphHistoryEntity entity);

    List<StrategyLineage> toDomainList(List<MorphHistoryEntity> entities);
}
