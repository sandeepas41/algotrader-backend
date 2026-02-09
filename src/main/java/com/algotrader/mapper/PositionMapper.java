package com.algotrader.mapper;

import com.algotrader.domain.model.Position;
import com.algotrader.entity.PositionEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper between Position domain model and PositionEntity.
 *
 * <p>Domain model has greeks (calculated at runtime, not persisted) and a derived
 * getType() method — both are ignored when mapping to entity. Entity has createdAt
 * which is ignored when mapping to domain.
 */
@Mapper
public interface PositionMapper {

    @Mapping(target = "createdAt", ignore = true)
    PositionEntity toEntity(Position position);

    @Mapping(target = "greeks", ignore = true)
    Position toDomain(PositionEntity entity);

    List<Position> toDomainList(List<PositionEntity> entities);
}
