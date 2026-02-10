package com.algotrader.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.MarketDataController;
import com.algotrader.api.dto.response.ChainExplorerResponse;
import com.algotrader.api.dto.response.InstrumentDumpResponse;
import com.algotrader.config.ApiResponseAdvice;
import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.model.Instrument;
import com.algotrader.domain.model.OptionChain;
import com.algotrader.mapper.InstrumentDumpMapper;
import com.algotrader.service.InstrumentService;
import com.algotrader.service.OptionChainService;
import com.algotrader.service.QuoteService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc tests for the MarketDataController.
 * Uses MockMvcBuilders.standaloneSetup since @WebMvcTest was removed in Spring Boot 4.0.
 */
@ExtendWith(MockitoExtension.class)
class MarketDataControllerTest {

    private MockMvc mockMvc;

    @Mock
    private OptionChainService optionChainService;

    @Mock
    private InstrumentService instrumentService;

    @Mock
    private InstrumentDumpMapper instrumentDumpMapper;

    @Mock
    private QuoteService quoteService;

    @BeforeEach
    void setUp() {
        MarketDataController controller =
                new MarketDataController(optionChainService, instrumentService, instrumentDumpMapper, quoteService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiResponseAdvice())
                .build();
    }

    @Test
    @DisplayName("GET /api/market-data/option-chain returns option chain for underlying and expiry")
    void getOptionChainReturnsChain() throws Exception {
        OptionChain chain = OptionChain.builder()
                .underlying("NIFTY")
                .spotPrice(BigDecimal.valueOf(22500))
                .expiry(LocalDate.of(2025, 2, 27))
                .atmStrike(BigDecimal.valueOf(22500))
                .entries(List.of())
                .build();

        when(optionChainService.getOptionChain(eq("NIFTY"), any(LocalDate.class)))
                .thenReturn(chain);

        mockMvc.perform(get("/api/market-data/option-chain")
                        .param("underlying", "NIFTY")
                        .param("expiry", "2025-02-27"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.underlying").value("NIFTY"))
                .andExpect(jsonPath("$.data.spotPrice").value(22500))
                .andExpect(jsonPath("$.data.atmStrike").value(22500));
    }

    @Test
    @DisplayName("GET /api/market-data/instruments searches by symbol prefix")
    void searchInstrumentsReturnsMatches() throws Exception {
        Instrument instrument = Instrument.builder()
                .token(256265L)
                .tradingSymbol("NIFTY24FEB22000CE")
                .name("NIFTY")
                .underlying("NIFTY")
                .type(InstrumentType.CE)
                .strike(BigDecimal.valueOf(22000))
                .exchange("NFO")
                .build();

        when(instrumentService.searchInstruments("NIFTY24FEB", null)).thenReturn(List.of(instrument));

        mockMvc.perform(get("/api/market-data/instruments").param("query", "NIFTY24FEB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].tradingSymbol").value("NIFTY24FEB22000CE"))
                .andExpect(jsonPath("$.data[0].token").value(256265));
    }

    @Test
    @DisplayName("GET /api/market-data/instruments/{token} returns instrument by token")
    void getInstrumentByTokenReturnsInstrument() throws Exception {
        Instrument instrument = Instrument.builder()
                .token(256265L)
                .tradingSymbol("NIFTY")
                .name("NIFTY 50")
                .exchange("NSE")
                .type(InstrumentType.EQ)
                .build();

        when(instrumentService.findByToken(256265L)).thenReturn(Optional.of(instrument));

        mockMvc.perform(get("/api/market-data/instruments/256265"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradingSymbol").value("NIFTY"))
                .andExpect(jsonPath("$.data.exchange").value("NSE"));
    }

    @Test
    @DisplayName("GET /api/market-data/instruments/{token} returns 404 when not found")
    void getInstrumentByTokenReturns404WhenNotFound() throws Exception {
        when(instrumentService.findByToken(999999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/market-data/instruments/999999")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/market-data/expiries/{underlying} returns expiry dates")
    void getExpiriesReturnsDates() throws Exception {
        when(instrumentService.getExpiries("NIFTY"))
                .thenReturn(List.of(LocalDate.of(2025, 2, 27), LocalDate.of(2025, 3, 6)));

        mockMvc.perform(get("/api/market-data/expiries/NIFTY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("2025-02-27"))
                .andExpect(jsonPath("$.data[1]").value("2025-03-06"));
    }

    @Test
    @DisplayName("GET /api/market-data/underlyings returns available underlyings")
    void getUnderlyingsReturnsSet() throws Exception {
        when(instrumentService.getAvailableUnderlyings()).thenReturn(Set.of("NIFTY", "BANKNIFTY", "FINNIFTY"));

        mockMvc.perform(get("/api/market-data/underlyings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET /api/market-data/chain returns chain explorer data")
    void getChainExplorerReturnsChain() throws Exception {
        ChainExplorerResponse response = ChainExplorerResponse.builder()
                .underlying("NIFTY")
                .displayName("NIFTY 50")
                .expiry(LocalDate.of(2025, 2, 27))
                .spotToken(256265L)
                .lotSize(75)
                .future(ChainExplorerResponse.FutureInfo.builder()
                        .token(9001L)
                        .tradingSymbol("NIFTY25FEBFUT")
                        .lotSize(75)
                        .build())
                .options(List.of(ChainExplorerResponse.OptionStrikeInfo.builder()
                        .strike(BigDecimal.valueOf(22000))
                        .call(ChainExplorerResponse.OptionSideInfo.builder()
                                .token(9002L)
                                .tradingSymbol("NIFTY25FEB22000CE")
                                .build())
                        .put(ChainExplorerResponse.OptionSideInfo.builder()
                                .token(9003L)
                                .tradingSymbol("NIFTY25FEB22000PE")
                                .build())
                        .lotSize(75)
                        .build()))
                .build();

        when(instrumentService.buildChainExplorer(eq("NIFTY"), any(LocalDate.class)))
                .thenReturn(response);

        mockMvc.perform(get("/api/market-data/chain")
                        .param("underlying", "NIFTY")
                        .param("expiry", "2025-02-27"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.underlying").value("NIFTY"))
                .andExpect(jsonPath("$.data.displayName").value("NIFTY 50"))
                .andExpect(jsonPath("$.data.spotToken").value(256265))
                .andExpect(jsonPath("$.data.lotSize").value(75))
                .andExpect(jsonPath("$.data.future.token").value(9001))
                .andExpect(jsonPath("$.data.future.tradingSymbol").value("NIFTY25FEBFUT"))
                .andExpect(jsonPath("$.data.options[0].strike").value(22000))
                .andExpect(jsonPath("$.data.options[0].call.token").value(9002))
                .andExpect(jsonPath("$.data.options[0].put.token").value(9003));
    }

    @Test
    @DisplayName("GET /api/market-data/instruments/dump returns all instruments with correct field names")
    void getInstrumentDumpReturnsEnvelope() throws Exception {
        Instrument nifty = Instrument.builder()
                .token(256265L)
                .tradingSymbol("NIFTY 50")
                .name("NIFTY 50")
                .underlying("NIFTY")
                .exchange("NSE")
                .segment("INDICES")
                .type(InstrumentType.EQ)
                .lotSize(1)
                .tickSize(BigDecimal.valueOf(0.05))
                .downloadDate(LocalDate.of(2026, 2, 10))
                .build();

        InstrumentDumpResponse response = InstrumentDumpResponse.builder()
                .instrumentToken(256265L)
                .tradingSymbol("NIFTY 50")
                .name("NIFTY 50")
                .underlying("NIFTY")
                .exchange("NSE")
                .segment("INDICES")
                .instrumentType("EQ")
                .lotSize(1)
                .tickSize(0.05)
                .build();

        Collection<Instrument> instruments = List.of(nifty);
        when(instrumentService.getAllCachedInstruments()).thenReturn(instruments);
        when(instrumentService.getDownloadDate()).thenReturn(LocalDate.of(2026, 2, 10));
        when(instrumentDumpMapper.toResponseList(any())).thenReturn(List.of(response));

        mockMvc.perform(get("/api/market-data/instruments/dump"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downloadDate").value("2026-02-10"))
                .andExpect(jsonPath("$.data.instruments").isArray())
                .andExpect(jsonPath("$.data.instruments[0].instrumentToken").value(256265))
                .andExpect(jsonPath("$.data.instruments[0].tradingSymbol").value("NIFTY 50"))
                .andExpect(jsonPath("$.data.instruments[0].instrumentType").value("EQ"))
                .andExpect(jsonPath("$.data.instruments[0].exchange").value("NSE"));
    }
}
