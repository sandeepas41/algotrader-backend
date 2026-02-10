package com.algotrader.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.api.dto.response.ChainExplorerResponse;
import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.model.Instrument;
import com.algotrader.entity.InstrumentEntity;
import com.algotrader.exception.BrokerException;
import com.algotrader.mapper.InstrumentMapper;
import com.algotrader.repository.jpa.InstrumentJpaRepository;
import com.algotrader.service.InstrumentService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link InstrumentService}.
 *
 * <p>Tests instrument loading from H2 vs Kite API download, in-memory cache
 * population, token/symbol/underlying lookups, and expiry filtering.
 * JPA repository and KiteConnect are mocked; InstrumentMapper is also mocked
 * to isolate InstrumentService logic.
 */
@ExtendWith(MockitoExtension.class)
class InstrumentServiceTest {

    @Mock
    private InstrumentJpaRepository instrumentJpaRepository;

    @Mock
    private InstrumentMapper instrumentMapper;

    @Mock
    private KiteConnect kiteConnect;

    private InstrumentService instrumentService;

    private static final LocalDate TODAY = LocalDate.now();

    @BeforeEach
    void setUp() {
        instrumentService = new InstrumentService(instrumentJpaRepository, instrumentMapper, kiteConnect);
    }

    // ---- Startup loading tests ----

    @Test
    @DisplayName("loadInstrumentsOnStartup: loads from H2 when today's instruments exist")
    void loadFromH2WhenInstrumentsExist() throws Throwable {
        when(instrumentJpaRepository.existsByDownloadDate(TODAY)).thenReturn(true);

        InstrumentEntity entity = buildEntity(1001L, "NIFTY24FEB22000CE", "NIFTY", "CE", "22000.0");
        when(instrumentJpaRepository.findByDownloadDate(TODAY)).thenReturn(List.of(entity));

        Instrument domain = buildDomain(1001L, "NIFTY24FEB22000CE", "NIFTY", InstrumentType.CE, "22000.0");
        when(instrumentMapper.toDomainList(List.of(entity))).thenReturn(List.of(domain));

        instrumentService.loadInstrumentsOnStartup();

        // Should NOT call Kite API
        verify(kiteConnect, never()).getInstruments(any());

        // Should be in token cache
        assertThat(instrumentService.findByToken(1001L)).isPresent();
        assertThat(instrumentService.findByToken(1001L).get().getTradingSymbol())
                .isEqualTo("NIFTY24FEB22000CE");

        // Should be in underlying cache (CE instrument)
        assertThat(instrumentService.getInstrumentsByUnderlying("NIFTY")).hasSize(1);
    }

    @Test
    @DisplayName("loadInstrumentsOnStartup: downloads from Kite API when H2 is empty")
    void downloadFromKiteWhenH2Empty() throws Throwable {
        when(instrumentJpaRepository.existsByDownloadDate(TODAY)).thenReturn(false);

        // Kite SDK returns instruments per exchange
        com.zerodhatech.models.Instrument kiteInst =
                buildKiteInstrument(2001L, "BANKNIFTY24FEB48000PE", "BANKNIFTY", "PE", "48000.0", "NFO", "NFO-OPT");
        when(kiteConnect.getInstruments("NFO")).thenReturn(List.of(kiteInst));
        when(kiteConnect.getInstruments("NSE")).thenReturn(List.of());
        when(kiteConnect.getInstruments("BSE")).thenReturn(List.of());

        // MapStruct toEntity is called during save
        InstrumentEntity entity = buildEntity(2001L, "BANKNIFTY24FEB48000PE", "BANKNIFTY", "PE", "48000.0");
        when(instrumentMapper.toEntity(any(Instrument.class))).thenReturn(entity);

        instrumentService.loadInstrumentsOnStartup();

        // Should call Kite API for all 3 exchanges
        verify(kiteConnect).getInstruments("NFO");
        verify(kiteConnect).getInstruments("NSE");
        verify(kiteConnect).getInstruments("BSE");

        // Should save to H2
        verify(instrumentJpaRepository).saveAll(anyList());

        // Should be in token cache
        assertThat(instrumentService.findByToken(2001L)).isPresent();

        // Should be in underlying cache (PE instrument)
        assertThat(instrumentService.getInstrumentsByUnderlying("BANKNIFTY")).hasSize(1);
    }

    @Test
    @DisplayName("loadInstrumentsOnStartup: throws BrokerException when Kite API fails")
    void throwsWhenKiteApiFails() throws Throwable {
        when(instrumentJpaRepository.existsByDownloadDate(TODAY)).thenReturn(false);
        // First exchange call (NFO) fails
        when(kiteConnect.getInstruments("NFO")).thenThrow(new KiteException("API error"));

        assertThatThrownBy(() -> instrumentService.loadInstrumentsOnStartup())
                .isInstanceOf(BrokerException.class)
                .hasMessageContaining("Failed to download instruments");
    }

    // ---- Cache lookup tests ----

    @Test
    @DisplayName("findByToken: returns empty when cache is not populated")
    void findByTokenEmptyCache() {
        assertThat(instrumentService.findByToken(9999L)).isEmpty();
    }

    @Test
    @DisplayName("getOptionsForUnderlying: filters by expiry correctly")
    void getOptionsFiltersByExpiry() {
        LocalDate expiry1 = LocalDate.of(2024, 2, 22);
        LocalDate expiry2 = LocalDate.of(2024, 2, 29);

        when(instrumentJpaRepository.existsByDownloadDate(TODAY)).thenReturn(true);

        InstrumentEntity e1 = buildEntity(3001L, "NIFTY24FEB22000CE", "NIFTY", "CE", "22000.0");
        InstrumentEntity e2 = buildEntity(3002L, "NIFTY24FEB22000PE", "NIFTY", "PE", "22000.0");
        InstrumentEntity e3 = buildEntity(3003L, "NIFTY24FEB29CE", "NIFTY", "CE", "22000.0");
        when(instrumentJpaRepository.findByDownloadDate(TODAY)).thenReturn(List.of(e1, e2, e3));

        Instrument d1 =
                buildDomainWithExpiry(3001L, "NIFTY24FEB22000CE", "NIFTY", InstrumentType.CE, "22000.0", expiry1);
        Instrument d2 =
                buildDomainWithExpiry(3002L, "NIFTY24FEB22000PE", "NIFTY", InstrumentType.PE, "22000.0", expiry1);
        Instrument d3 = buildDomainWithExpiry(3003L, "NIFTY24FEB29CE", "NIFTY", InstrumentType.CE, "22000.0", expiry2);
        when(instrumentMapper.toDomainList(List.of(e1, e2, e3))).thenReturn(List.of(d1, d2, d3));

        instrumentService.loadInstrumentsOnStartup();

        // Filter by expiry1 should return 2 instruments
        List<Instrument> expiry1Options = instrumentService.getOptionsForUnderlying("NIFTY", expiry1);
        assertThat(expiry1Options).hasSize(2);

        // Filter by expiry2 should return 1 instrument
        List<Instrument> expiry2Options = instrumentService.getOptionsForUnderlying("NIFTY", expiry2);
        assertThat(expiry2Options).hasSize(1);
    }

    @Test
    @DisplayName("getExpiries: returns sorted distinct expiry dates")
    void getExpiriesReturnsSortedDistinct() {
        LocalDate expiry1 = LocalDate.of(2024, 2, 22);
        LocalDate expiry2 = LocalDate.of(2024, 2, 29);
        LocalDate expiry3 = LocalDate.of(2024, 3, 7);

        when(instrumentJpaRepository.existsByDownloadDate(TODAY)).thenReturn(true);

        InstrumentEntity e1 = buildEntity(4001L, "SYM1", "NIFTY", "CE", "22000.0");
        InstrumentEntity e2 = buildEntity(4002L, "SYM2", "NIFTY", "PE", "22000.0");
        InstrumentEntity e3 = buildEntity(4003L, "SYM3", "NIFTY", "CE", "23000.0");
        when(instrumentJpaRepository.findByDownloadDate(TODAY)).thenReturn(List.of(e1, e2, e3));

        // e1 and e2 share expiry1, e3 has expiry3 — no expiry2 to test distinct
        Instrument d1 = buildDomainWithExpiry(4001L, "SYM1", "NIFTY", InstrumentType.CE, "22000.0", expiry1);
        Instrument d2 = buildDomainWithExpiry(4002L, "SYM2", "NIFTY", InstrumentType.PE, "22000.0", expiry1);
        Instrument d3 = buildDomainWithExpiry(4003L, "SYM3", "NIFTY", InstrumentType.CE, "23000.0", expiry3);
        when(instrumentMapper.toDomainList(List.of(e1, e2, e3))).thenReturn(List.of(d1, d2, d3));

        instrumentService.loadInstrumentsOnStartup();

        List<LocalDate> expiries = instrumentService.getExpiries("NIFTY");
        assertThat(expiries).containsExactly(expiry1, expiry3);
    }

    @Test
    @DisplayName("getExpiries: returns empty for unknown underlying")
    void getExpiriesUnknownUnderlying() {
        assertThat(instrumentService.getExpiries("UNKNOWN")).isEmpty();
    }

    @Test
    @DisplayName("searchInstruments: finds instruments by symbol prefix")
    void searchInstrumentsPrefix() {
        when(instrumentJpaRepository.existsByDownloadDate(TODAY)).thenReturn(true);

        InstrumentEntity e1 = buildEntity(5001L, "NIFTY24FEB22000CE", "NIFTY", "CE", "22000.0");
        InstrumentEntity e2 = buildEntity(5002L, "BANKNIFTY24FEB48000PE", "BANKNIFTY", "PE", "48000.0");
        InstrumentEntity e3 = buildEntity(5003L, "NIFTY24FEB23000PE", "NIFTY", "PE", "23000.0");
        when(instrumentJpaRepository.findByDownloadDate(TODAY)).thenReturn(List.of(e1, e2, e3));

        Instrument d1 = buildDomain(5001L, "NIFTY24FEB22000CE", "NIFTY", InstrumentType.CE, "22000.0");
        Instrument d2 = buildDomain(5002L, "BANKNIFTY24FEB48000PE", "BANKNIFTY", InstrumentType.PE, "48000.0");
        Instrument d3 = buildDomain(5003L, "NIFTY24FEB23000PE", "NIFTY", InstrumentType.PE, "23000.0");
        when(instrumentMapper.toDomainList(List.of(e1, e2, e3))).thenReturn(List.of(d1, d2, d3));

        instrumentService.loadInstrumentsOnStartup();

        // Search "NIFTY" should return NIFTY instruments only (not BANKNIFTY)
        List<Instrument> results = instrumentService.searchInstruments("NIFTY24");
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(i -> i.getTradingSymbol().startsWith("NIFTY24"));

        // Search "BANK" should return BANKNIFTY
        List<Instrument> bankResults = instrumentService.searchInstruments("BANK");
        assertThat(bankResults).hasSize(1);

        // Case-insensitive
        List<Instrument> lowerResults = instrumentService.searchInstruments("nifty24");
        assertThat(lowerResults).hasSize(2);
    }

    @Test
    @DisplayName("getCachedInstrumentCount: returns correct count")
    void getCachedInstrumentCount() {
        when(instrumentJpaRepository.existsByDownloadDate(TODAY)).thenReturn(true);

        InstrumentEntity e1 = buildEntity(6001L, "SYM1", "NIFTY", "CE", "22000.0");
        InstrumentEntity e2 = buildEntity(6002L, "SYM2", "NIFTY", "PE", "22000.0");
        when(instrumentJpaRepository.findByDownloadDate(TODAY)).thenReturn(List.of(e1, e2));

        Instrument d1 = buildDomain(6001L, "SYM1", "NIFTY", InstrumentType.CE, "22000.0");
        Instrument d2 = buildDomain(6002L, "SYM2", "NIFTY", InstrumentType.PE, "22000.0");
        when(instrumentMapper.toDomainList(List.of(e1, e2))).thenReturn(List.of(d1, d2));

        instrumentService.loadInstrumentsOnStartup();

        assertThat(instrumentService.getCachedInstrumentCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("getAvailableUnderlyings: returns underlying names with options")
    void getAvailableUnderlyings() {
        when(instrumentJpaRepository.existsByDownloadDate(TODAY)).thenReturn(true);

        InstrumentEntity e1 = buildEntity(7001L, "SYM1", "NIFTY", "CE", "22000.0");
        InstrumentEntity e2 = buildEntity(7002L, "SYM2", "BANKNIFTY", "PE", "48000.0");
        when(instrumentJpaRepository.findByDownloadDate(TODAY)).thenReturn(List.of(e1, e2));

        Instrument d1 = buildDomain(7001L, "SYM1", "NIFTY", InstrumentType.CE, "22000.0");
        Instrument d2 = buildDomain(7002L, "SYM2", "BANKNIFTY", InstrumentType.PE, "48000.0");
        when(instrumentMapper.toDomainList(List.of(e1, e2))).thenReturn(List.of(d1, d2));

        instrumentService.loadInstrumentsOnStartup();

        assertThat(instrumentService.getAvailableUnderlyings()).containsExactlyInAnyOrder("NIFTY", "BANKNIFTY");
    }

    @Test
    @DisplayName("underlyingCache: excludes non-option instruments (FUT, EQ)")
    void underlyingCacheExcludesNonOptions() {
        when(instrumentJpaRepository.existsByDownloadDate(TODAY)).thenReturn(true);

        InstrumentEntity e1 = buildEntity(8001L, "NIFTY24FEBFUT", "NIFTY", "FUT", "0");
        InstrumentEntity e2 = buildEntity(8002L, "RELIANCE", "RELIANCE", "EQ", "0");
        InstrumentEntity e3 = buildEntity(8003L, "NIFTY24FEB22000CE", "NIFTY", "CE", "22000.0");
        when(instrumentJpaRepository.findByDownloadDate(TODAY)).thenReturn(List.of(e1, e2, e3));

        Instrument d1 = buildDomain(8001L, "NIFTY24FEBFUT", "NIFTY", InstrumentType.FUT, "0");
        Instrument d2 = buildDomain(8002L, "RELIANCE", "RELIANCE", InstrumentType.EQ, "0");
        Instrument d3 = buildDomain(8003L, "NIFTY24FEB22000CE", "NIFTY", InstrumentType.CE, "22000.0");
        when(instrumentMapper.toDomainList(List.of(e1, e2, e3))).thenReturn(List.of(d1, d2, d3));

        instrumentService.loadInstrumentsOnStartup();

        // Token cache has all 3
        assertThat(instrumentService.getCachedInstrumentCount()).isEqualTo(3);

        // Underlying cache only has CE for NIFTY (FUT and EQ excluded)
        assertThat(instrumentService.getInstrumentsByUnderlying("NIFTY")).hasSize(1);
        assertThat(instrumentService
                        .getInstrumentsByUnderlying("NIFTY")
                        .getFirst()
                        .getType())
                .isEqualTo(InstrumentType.CE);

        // RELIANCE has no options, so not in underlying cache
        assertThat(instrumentService.getInstrumentsByUnderlying("RELIANCE")).isEmpty();
    }

    // ---- Chain explorer tests ----

    @Test
    @DisplayName("buildChainExplorer: returns FUT + paired CE/PE strikes sorted by strike")
    void buildChainExplorerPairsCEAndPE() {
        LocalDate expiry = LocalDate.of(2024, 2, 22);

        when(instrumentJpaRepository.existsByDownloadDate(TODAY)).thenReturn(true);

        InstrumentEntity e1 = buildEntity(9001L, "NIFTY24FEBFUT", "NIFTY", "FUT", "0");
        InstrumentEntity e2 = buildEntity(9002L, "NIFTY24FEB22000CE", "NIFTY", "CE", "22000.0");
        InstrumentEntity e3 = buildEntity(9003L, "NIFTY24FEB22000PE", "NIFTY", "PE", "22000.0");
        InstrumentEntity e4 = buildEntity(9004L, "NIFTY24FEB22500CE", "NIFTY", "CE", "22500.0");
        InstrumentEntity e5 = buildEntity(9005L, "NIFTY24FEB22500PE", "NIFTY", "PE", "22500.0");
        // Spot index instrument
        InstrumentEntity e6 = buildEntity(256265L, "NIFTY 50", "NIFTY", "EQ", "0");
        e6.setExchange("NSE");
        e6.setSegment("INDICES");
        when(instrumentJpaRepository.findByDownloadDate(TODAY)).thenReturn(List.of(e1, e2, e3, e4, e5, e6));

        Instrument d1 = buildDomainWithExpiry(9001L, "NIFTY24FEBFUT", "NIFTY", InstrumentType.FUT, "0", expiry);
        Instrument d2 =
                buildDomainWithExpiry(9002L, "NIFTY24FEB22000CE", "NIFTY", InstrumentType.CE, "22000.0", expiry);
        Instrument d3 =
                buildDomainWithExpiry(9003L, "NIFTY24FEB22000PE", "NIFTY", InstrumentType.PE, "22000.0", expiry);
        Instrument d4 =
                buildDomainWithExpiry(9004L, "NIFTY24FEB22500CE", "NIFTY", InstrumentType.CE, "22500.0", expiry);
        Instrument d5 =
                buildDomainWithExpiry(9005L, "NIFTY24FEB22500PE", "NIFTY", InstrumentType.PE, "22500.0", expiry);
        Instrument spotInst = Instrument.builder()
                .token(256265L)
                .tradingSymbol("NIFTY 50")
                .name("NIFTY 50")
                .underlying("NIFTY")
                .type(InstrumentType.EQ)
                .exchange("NSE")
                .segment("INDICES")
                .lotSize(1)
                .downloadDate(TODAY)
                .build();
        when(instrumentMapper.toDomainList(List.of(e1, e2, e3, e4, e5, e6)))
                .thenReturn(List.of(d1, d2, d3, d4, d5, spotInst));

        instrumentService.loadInstrumentsOnStartup();

        ChainExplorerResponse chain = instrumentService.buildChainExplorer("NIFTY", expiry);

        assertThat(chain.getUnderlying()).isEqualTo("NIFTY");
        assertThat(chain.getDisplayName()).isEqualTo("NIFTY 50");
        assertThat(chain.getSpotToken()).isEqualTo(256265L);
        assertThat(chain.getExpiry()).isEqualTo(expiry);

        // Future
        assertThat(chain.getFuture()).isNotNull();
        assertThat(chain.getFuture().getToken()).isEqualTo(9001L);
        assertThat(chain.getFuture().getTradingSymbol()).isEqualTo("NIFTY24FEBFUT");

        // Options: 2 strikes, sorted ascending
        assertThat(chain.getOptions()).hasSize(2);
        assertThat(chain.getOptions().get(0).getStrike()).isEqualByComparingTo(new BigDecimal("22000.0"));
        assertThat(chain.getOptions().get(0).getCall()).isNotNull();
        assertThat(chain.getOptions().get(0).getCall().getToken()).isEqualTo(9002L);
        assertThat(chain.getOptions().get(0).getPut()).isNotNull();
        assertThat(chain.getOptions().get(0).getPut().getToken()).isEqualTo(9003L);

        assertThat(chain.getOptions().get(1).getStrike()).isEqualByComparingTo(new BigDecimal("22500.0"));
        assertThat(chain.getOptions().get(1).getCall().getToken()).isEqualTo(9004L);
        assertThat(chain.getOptions().get(1).getPut().getToken()).isEqualTo(9005L);
    }

    @Test
    @DisplayName("buildChainExplorer: returns empty options when no derivatives exist")
    void buildChainExplorerEmptyChain() {
        LocalDate expiry = LocalDate.of(2024, 6, 15);

        ChainExplorerResponse chain = instrumentService.buildChainExplorer("UNKNOWN", expiry);

        assertThat(chain.getUnderlying()).isEqualTo("UNKNOWN");
        assertThat(chain.getDisplayName()).isEqualTo("UNKNOWN");
        assertThat(chain.getSpotToken()).isNull();
        assertThat(chain.getFuture()).isNull();
        assertThat(chain.getOptions()).isEmpty();
    }

    // ---- Spot cache tests ----

    @Test
    @DisplayName("spotCache: equities keyed by tradingSymbol, indices by NFO underlying")
    void spotCachePopulatedCorrectly() {
        when(instrumentJpaRepository.existsByDownloadDate(TODAY)).thenReturn(true);

        // NSE equity
        InstrumentEntity eq = buildEntity(100001L, "ADANIPORTS", "ADANIPORTS", "EQ", "0");
        eq.setExchange("NSE");
        eq.setSegment("NSE");

        // NSE index
        InstrumentEntity idx = buildEntity(256265L, "NIFTY 50", "NIFTY", "EQ", "0");
        idx.setExchange("NSE");
        idx.setSegment("INDICES");

        when(instrumentJpaRepository.findByDownloadDate(TODAY)).thenReturn(List.of(eq, idx));

        Instrument eqDomain = Instrument.builder()
                .token(100001L)
                .tradingSymbol("ADANIPORTS")
                .name("ADANI PORT & SEZ")
                .underlying("ADANIPORTS")
                .type(InstrumentType.EQ)
                .exchange("NSE")
                .segment("NSE")
                .lotSize(1)
                .downloadDate(TODAY)
                .build();
        Instrument idxDomain = Instrument.builder()
                .token(256265L)
                .tradingSymbol("NIFTY 50")
                .name("NIFTY 50")
                .underlying("NIFTY")
                .type(InstrumentType.EQ)
                .exchange("NSE")
                .segment("INDICES")
                .lotSize(1)
                .downloadDate(TODAY)
                .build();
        when(instrumentMapper.toDomainList(List.of(eq, idx))).thenReturn(List.of(eqDomain, idxDomain));

        instrumentService.loadInstrumentsOnStartup();

        // Equity keyed by tradingSymbol
        assertThat(instrumentService.getSpotInstrument("ADANIPORTS")).isPresent();
        assertThat(instrumentService.getSpotInstrument("ADANIPORTS").get().getName())
                .isEqualTo("ADANI PORT & SEZ");

        // Index keyed by NFO underlying name
        assertThat(instrumentService.getSpotInstrument("NIFTY")).isPresent();
        assertThat(instrumentService.getSpotInstrument("NIFTY").get().getToken())
                .isEqualTo(256265L);

        // Non-existent underlying
        assertThat(instrumentService.getSpotInstrument("UNKNOWN")).isEmpty();
    }

    @Test
    @DisplayName("searchInstruments: exchange filter restricts results")
    void searchInstrumentsWithExchangeFilter() {
        when(instrumentJpaRepository.existsByDownloadDate(TODAY)).thenReturn(true);

        InstrumentEntity e1 = buildEntity(10001L, "RELIANCE", "RELIANCE", "EQ", "0");
        e1.setExchange("NSE");
        e1.setSegment("NSE");
        InstrumentEntity e2 = buildEntity(10002L, "RELIANCE26FEBFUT", "RELIANCE", "FUT", "0");
        when(instrumentJpaRepository.findByDownloadDate(TODAY)).thenReturn(List.of(e1, e2));

        Instrument d1 = Instrument.builder()
                .token(10001L)
                .tradingSymbol("RELIANCE")
                .name("RELIANCE INDUSTRIES")
                .underlying("RELIANCE")
                .type(InstrumentType.EQ)
                .exchange("NSE")
                .segment("NSE")
                .lotSize(1)
                .downloadDate(TODAY)
                .build();
        Instrument d2 = buildDomain(10002L, "RELIANCE26FEBFUT", "RELIANCE", InstrumentType.FUT, "0");
        when(instrumentMapper.toDomainList(List.of(e1, e2))).thenReturn(List.of(d1, d2));

        instrumentService.loadInstrumentsOnStartup();

        // No exchange filter — both results
        assertThat(instrumentService.searchInstruments("RELIANCE", null)).hasSize(2);

        // NSE filter — only equity
        assertThat(instrumentService.searchInstruments("RELIANCE", "NSE")).hasSize(1);
        assertThat(instrumentService
                        .searchInstruments("RELIANCE", "NSE")
                        .getFirst()
                        .getExchange())
                .isEqualTo("NSE");

        // NFO filter — only future
        assertThat(instrumentService.searchInstruments("RELIANCE", "NFO")).hasSize(1);
        assertThat(instrumentService
                        .searchInstruments("RELIANCE", "NFO")
                        .getFirst()
                        .getType())
                .isEqualTo(InstrumentType.FUT);
    }

    // ---- Builder helpers ----

    private InstrumentEntity buildEntity(
            Long token, String tradingSymbol, String underlying, String type, String strike) {
        return InstrumentEntity.builder()
                .token(token)
                .tradingSymbol(tradingSymbol)
                .underlying(underlying)
                .instrumentType(type)
                .strike(new BigDecimal(strike))
                .exchange("NFO")
                .segment("NFO-OPT")
                .lotSize(75)
                .tickSize(new BigDecimal("0.05"))
                .downloadDate(TODAY)
                .build();
    }

    private Instrument buildDomain(
            Long token, String tradingSymbol, String underlying, InstrumentType type, String strike) {
        return Instrument.builder()
                .token(token)
                .tradingSymbol(tradingSymbol)
                .underlying(underlying)
                .type(type)
                .strike(new BigDecimal(strike))
                .exchange("NFO")
                .segment("NFO-OPT")
                .lotSize(75)
                .tickSize(new BigDecimal("0.05"))
                .downloadDate(TODAY)
                .build();
    }

    private Instrument buildDomainWithExpiry(
            Long token, String tradingSymbol, String underlying, InstrumentType type, String strike, LocalDate expiry) {
        return Instrument.builder()
                .token(token)
                .tradingSymbol(tradingSymbol)
                .underlying(underlying)
                .type(type)
                .strike(new BigDecimal(strike))
                .expiry(expiry)
                .exchange("NFO")
                .segment("NFO-OPT")
                .lotSize(75)
                .tickSize(new BigDecimal("0.05"))
                .downloadDate(TODAY)
                .build();
    }

    @SuppressWarnings("deprecation")
    private com.zerodhatech.models.Instrument buildKiteInstrument(
            long token,
            String tradingSymbol,
            String name,
            String instrumentType,
            String strike,
            String exchange,
            String segment) {
        com.zerodhatech.models.Instrument ki = new com.zerodhatech.models.Instrument();
        ki.instrument_token = token;
        ki.tradingsymbol = tradingSymbol;
        ki.name = name;
        ki.instrument_type = instrumentType;
        ki.strike = strike;
        ki.exchange = exchange;
        ki.segment = segment;
        ki.lot_size = 75;
        ki.tick_size = 0.05;
        // Kite expiry is java.util.Date — set to a future date
        ki.expiry = new Date(2024 - 1900, 1, 22); // Feb 22, 2024
        return ki;
    }
}
