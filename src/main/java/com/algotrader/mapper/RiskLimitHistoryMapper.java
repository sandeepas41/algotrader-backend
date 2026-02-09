package com.algotrader.mapper;

import com.algotrader.domain.model.RiskLimitHistory;
import com.algotrader.entity.RiskLimitHistoryEntity;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper between RiskLimitHistory domain model and RiskLimitHistoryEntity.
 * Straightforward 1:1 field mapping.
 */
@Mapper
public interface RiskLimitHistoryMapper {

    RiskLimitHistoryEntity toEntity(RiskLimitHistory riskLimitHistory);

    RiskLimitHistory toDomain(RiskLimitHistoryEntity entity);

    List<RiskLimitHistory> toDomainList(List<RiskLimitHistoryEntity> entities);
}
