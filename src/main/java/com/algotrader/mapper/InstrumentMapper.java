package com.algotrader.mapper;

import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.model.Instrument;
import com.algotrader.entity.InstrumentEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * MapStruct mapper between Instrument domain model and InstrumentEntity.
 *
 * <p>Entity uses String for instrumentType (Kite has many types beyond our enum),
 * while domain model uses InstrumentType enum. This mapper handles the conversion,
 * returning null for unrecognized Kite instrument types.
 */
@Mapper
public interface InstrumentMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(source = "type", target = "instrumentType", qualifiedByName = "instrumentTypeToString")
    InstrumentEntity toEntity(Instrument instrument);

    @Mapping(source = "instrumentType", target = "type", qualifiedByName = "stringToInstrumentType")
    Instrument toDomain(InstrumentEntity entity);

    List<Instrument> toDomainList(List<InstrumentEntity> entities);

    @Named("instrumentTypeToString")
    default String instrumentTypeToString(InstrumentType type) {
        return type != null ? type.name() : null;
    }

    @Named("stringToInstrumentType")
    default InstrumentType stringToInstrumentType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return InstrumentType.valueOf(type);
        } catch (IllegalArgumentException e) {
            // Kite instrument dump has types beyond our enum (e.g., "EQ", "FUT", "CE", "PE")
            return null;
        }
    }
}
