package com.algotrader.mapper;

import com.algotrader.domain.model.Order;
import com.algotrader.entity.OrderEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper between Order domain model and OrderEntity.
 *
 * <p>Domain uses 'type' for OrderType, entity uses 'orderType' — mapped explicitly.
 * Entity has createdAt (DB-managed) which is ignored when mapping to domain.
 */
@Mapper
public interface OrderMapper {

    @Mapping(source = "type", target = "orderType")
    @Mapping(target = "createdAt", ignore = true)
    OrderEntity toEntity(Order order);

    @Mapping(source = "orderType", target = "type")
    Order toDomain(OrderEntity entity);

    List<Order> toDomainList(List<OrderEntity> entities);
}
