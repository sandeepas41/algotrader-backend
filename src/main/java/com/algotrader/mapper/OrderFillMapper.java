package com.algotrader.mapper;

import com.algotrader.domain.model.OrderFill;
import com.algotrader.entity.OrderFillEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper between OrderFill domain model and OrderFillEntity.
 *
 * <p>Domain uses 'id' for the fill identifier. Entity has createdAt (DB-managed)
 * which is ignored when mapping to domain.
 */
@Mapper
public interface OrderFillMapper {

    @Mapping(target = "createdAt", ignore = true)
    OrderFillEntity toEntity(OrderFill orderFill);

    OrderFill toDomain(OrderFillEntity entity);

    List<OrderFill> toDomainList(List<OrderFillEntity> entities);
}
