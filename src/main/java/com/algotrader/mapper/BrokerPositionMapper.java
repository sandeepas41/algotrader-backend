package com.algotrader.mapper;

import com.algotrader.api.dto.response.BrokerPositionResponse;
import com.algotrader.domain.model.Position;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for converting Position domain model to BrokerPositionResponse DTO.
 * The allocatedQuantity field is set separately after mapping (not in the Position model).
 */
@Mapper(componentModel = "spring")
public interface BrokerPositionMapper {

    @Mapping(target = "allocatedQuantity", ignore = true)
    BrokerPositionResponse toResponse(Position position);
}
