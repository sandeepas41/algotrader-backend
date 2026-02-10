package com.algotrader.mapper;

import com.algotrader.api.dto.response.InstrumentDumpResponse;
import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.model.Instrument;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/**
 * MapStruct mapper for converting Instrument domain model to the bulk dump DTO.
 *
 * <p>Handles field renaming (token → instrumentToken) and type conversions
 * (BigDecimal → Double, LocalDate → ISO String, InstrumentType → String)
 * required by the FE IndexedDB schema.
 */
@Mapper
public interface InstrumentDumpMapper {

    @Mapping(source = "token", target = "instrumentToken")
    @Mapping(source = "type", target = "instrumentType", qualifiedByName = "typeToString")
    @Mapping(source = "strike", target = "strike", qualifiedByName = "bigDecimalToDouble")
    @Mapping(source = "expiry", target = "expiry", qualifiedByName = "localDateToString")
    @Mapping(source = "tickSize", target = "tickSize", qualifiedByName = "bigDecimalToDoubleValue")
    InstrumentDumpResponse toResponse(Instrument instrument);

    List<InstrumentDumpResponse> toResponseList(List<Instrument> instruments);

    @Named("typeToString")
    default String typeToString(InstrumentType type) {
        return type != null ? type.name() : null;
    }

    @Named("bigDecimalToDouble")
    default Double bigDecimalToDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : null;
    }

    @Named("bigDecimalToDoubleValue")
    default double bigDecimalToDoubleValue(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
    }

    @Named("localDateToString")
    default String localDateToString(LocalDate date) {
        return date != null ? date.toString() : null;
    }
}
