package com.algotrader.unit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.api.dto.response.InstrumentDumpResponse;
import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.model.Instrument;
import com.algotrader.mapper.InstrumentDumpMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * Unit tests for {@link InstrumentDumpMapper}.
 * Verifies field renaming (token → instrumentToken) and type conversions
 * (BigDecimal → Double, LocalDate → String, InstrumentType → String).
 */
class InstrumentDumpMapperTest {

    private InstrumentDumpMapper instrumentDumpMapper;

    @BeforeEach
    void setUp() {
        instrumentDumpMapper = Mappers.getMapper(InstrumentDumpMapper.class);
    }

    @Test
    @DisplayName("toResponse: maps all fields from a complete CE option instrument")
    void mapsCompleteOptionInstrument() {
        Instrument instrument = Instrument.builder()
                .token(12345678L)
                .tradingSymbol("NIFTY26FEB22000CE")
                .name("NIFTY")
                .underlying("NIFTY")
                .type(InstrumentType.CE)
                .strike(BigDecimal.valueOf(22000))
                .expiry(LocalDate.of(2026, 2, 27))
                .exchange("NFO")
                .segment("NFO-OPT")
                .lotSize(75)
                .tickSize(BigDecimal.valueOf(0.05))
                .downloadDate(LocalDate.of(2026, 2, 10))
                .build();

        InstrumentDumpResponse response = instrumentDumpMapper.toResponse(instrument);

        assertThat(response.getInstrumentToken()).isEqualTo(12345678L);
        assertThat(response.getTradingSymbol()).isEqualTo("NIFTY26FEB22000CE");
        assertThat(response.getName()).isEqualTo("NIFTY");
        assertThat(response.getUnderlying()).isEqualTo("NIFTY");
        assertThat(response.getInstrumentType()).isEqualTo("CE");
        assertThat(response.getStrike()).isEqualTo(22000.0);
        assertThat(response.getExpiry()).isEqualTo("2026-02-27");
        assertThat(response.getExchange()).isEqualTo("NFO");
        assertThat(response.getSegment()).isEqualTo("NFO-OPT");
        assertThat(response.getLotSize()).isEqualTo(75);
        assertThat(response.getTickSize()).isEqualTo(0.05);
    }

    @Test
    @DisplayName("toResponse: maps NSE equity with null strike and expiry")
    void mapsEquityWithNulls() {
        Instrument instrument = Instrument.builder()
                .token(256265L)
                .tradingSymbol("NIFTY 50")
                .name("NIFTY 50")
                .underlying("NIFTY")
                .type(InstrumentType.EQ)
                .strike(null)
                .expiry(null)
                .exchange("NSE")
                .segment("INDICES")
                .lotSize(1)
                .tickSize(BigDecimal.valueOf(0.05))
                .build();

        InstrumentDumpResponse response = instrumentDumpMapper.toResponse(instrument);

        assertThat(response.getInstrumentToken()).isEqualTo(256265L);
        assertThat(response.getInstrumentType()).isEqualTo("EQ");
        assertThat(response.getStrike()).isNull();
        assertThat(response.getExpiry()).isNull();
    }

    @Test
    @DisplayName("toResponse: handles null instrumentType gracefully")
    void handlesNullType() {
        Instrument instrument = Instrument.builder()
                .token(999L)
                .tradingSymbol("UNKNOWN")
                .type(null)
                .tickSize(BigDecimal.valueOf(0.01))
                .build();

        InstrumentDumpResponse response = instrumentDumpMapper.toResponse(instrument);

        assertThat(response.getInstrumentType()).isNull();
    }

    @Test
    @DisplayName("toResponse: returns null for null instrument")
    void handlesNullInstrument() {
        assertThat(instrumentDumpMapper.toResponse(null)).isNull();
    }

    @Test
    @DisplayName("toResponseList: converts a list of instruments")
    void convertsListCorrectly() {
        Instrument nifty = Instrument.builder()
                .token(256265L)
                .tradingSymbol("NIFTY 50")
                .type(InstrumentType.EQ)
                .tickSize(BigDecimal.valueOf(0.05))
                .build();

        Instrument bnf = Instrument.builder()
                .token(260105L)
                .tradingSymbol("NIFTY BANK")
                .type(InstrumentType.EQ)
                .tickSize(BigDecimal.valueOf(0.05))
                .build();

        List<InstrumentDumpResponse> responses = instrumentDumpMapper.toResponseList(List.of(nifty, bnf));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getInstrumentToken()).isEqualTo(256265L);
        assertThat(responses.get(1).getInstrumentToken()).isEqualTo(260105L);
    }

    @Test
    @DisplayName("toResponseList: returns null for null list")
    void listHandlesNull() {
        assertThat(instrumentDumpMapper.toResponseList(null)).isNull();
    }
}
