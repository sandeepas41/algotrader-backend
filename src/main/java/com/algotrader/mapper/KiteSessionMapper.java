package com.algotrader.mapper;

import com.algotrader.entity.KiteSessionEntity;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for KiteSessionEntity.
 *
 * <p>Provides mapping between JPA entity and simple value extractions used by
 * {@link com.algotrader.broker.KiteAuthService} when persisting and restoring
 * Kite access tokens from H2.
 */
@Mapper
public interface KiteSessionMapper {

    KiteSessionEntity copy(KiteSessionEntity source);
}
