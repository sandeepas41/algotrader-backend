package com.algotrader.mapper;

import com.algotrader.domain.model.Strategy;
import com.algotrader.entity.StrategyEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper between Strategy domain model and StrategyEntity.
 *
 * <p>Legs and adjustment rules are stored in separate tables (strategy_legs, adjustment_rules)
 * and loaded separately by the service layer. They are ignored in this entity mapping.
 * The config field is a JSON string in both domain and entity — no conversion needed.
 */
@Mapper
public interface StrategyMapper {

    @Mapping(target = "createdAt", ignore = true)
    StrategyEntity toEntity(Strategy strategy);

    @Mapping(target = "legs", ignore = true)
    @Mapping(target = "adjustmentRules", ignore = true)
    Strategy toDomain(StrategyEntity entity);

    List<Strategy> toDomainList(List<StrategyEntity> entities);
}
