package com.algotrader.mapper;

import com.algotrader.api.dto.request.WatchlistConfigRequest;
import com.algotrader.api.dto.response.WatchlistConfigResponse;
import com.algotrader.domain.model.WatchlistConfig;
import com.algotrader.entity.WatchlistConfigEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

/**
 * MapStruct mapper for watchlist config domain models, JPA entities, and DTOs.
 *
 * <p>Provides bidirectional mapping between:
 * <ul>
 *   <li>{@link WatchlistConfig} domain model <-> {@link WatchlistConfigEntity}</li>
 *   <li>{@link WatchlistConfigRequest} DTO -> {@link WatchlistConfig} domain model</li>
 *   <li>{@link WatchlistConfig} domain model -> {@link WatchlistConfigResponse} DTO</li>
 * </ul>
 */
@Mapper
public interface WatchlistConfigMapper {

    // Entity <-> Domain
    WatchlistConfig toDomain(WatchlistConfigEntity entity);

    WatchlistConfigEntity toEntity(WatchlistConfig domain);

    List<WatchlistConfig> toDomainList(List<WatchlistConfigEntity> entities);

    // Request DTO -> Domain
    WatchlistConfig toDomain(WatchlistConfigRequest request);

    // Domain -> Response DTO
    WatchlistConfigResponse toResponse(WatchlistConfig domain);

    List<WatchlistConfigResponse> toResponseList(List<WatchlistConfig> domains);

    // Update existing domain from request
    void updateFromRequest(WatchlistConfigRequest request, @MappingTarget WatchlistConfig domain);
}
