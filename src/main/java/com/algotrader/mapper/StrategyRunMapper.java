package com.algotrader.mapper;

import com.algotrader.domain.model.PnLSegment;
import com.algotrader.domain.model.StrategyRun;
import com.algotrader.entity.StrategyRunEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * MapStruct mapper between StrategyRun domain model and StrategyRunEntity.
 *
 * <p>PnL segments (List&lt;PnLSegment&gt;) are stored as a JSON array string in the entity.
 * This mapper handles the JSON conversion via JsonHelper.
 */
@Mapper
public interface StrategyRunMapper {

    @Mapping(source = "pnlSegments", target = "pnlSegments", qualifiedByName = "pnlSegmentsToJson")
    @Mapping(target = "createdAt", ignore = true)
    StrategyRunEntity toEntity(StrategyRun strategyRun);

    @Mapping(source = "pnlSegments", target = "pnlSegments", qualifiedByName = "jsonToPnlSegments")
    StrategyRun toDomain(StrategyRunEntity entity);

    List<StrategyRun> toDomainList(List<StrategyRunEntity> entities);

    @Named("pnlSegmentsToJson")
    default String pnlSegmentsToJson(List<PnLSegment> segments) {
        return JsonHelper.toJson(segments);
    }

    @Named("jsonToPnlSegments")
    default List<PnLSegment> jsonToPnlSegments(String json) {
        return JsonHelper.fromJsonList(json, PnLSegment.class);
    }
}
