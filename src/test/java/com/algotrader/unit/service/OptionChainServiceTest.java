package com.algotrader.unit.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.algotrader.core.processor.GreeksCalculator;
import com.algotrader.core.processor.IVCalculator;
import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.model.Greeks;
import com.algotrader.domain.model.Instrument;
import com.algotrader.domain.model.OptionChain;
import com.algotrader.domain.model.OptionChainEntry;
import com.algotrader.domain.model.OptionData;
import com.algotrader.service.InstrumentService;
import com.algotrader.service.OptionChainService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Depth;
import com.zerodhatech.models.MarketDepth;
import com.zerodhatech.models.Quote;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for OptionChainService. Validates option chain construction from
 * mock instruments and quotes, cache behavior, ATM strike selection, and
 * delta-based strike lookup.
 */
@ExtendWith(MockitoExtension.class)
class OptionChainServiceTest {

    @Mock
    private InstrumentService instrumentService;

    @Mock
    private KiteConnect kiteConnect;

    private GreeksCalculator greeksCalculator;
    private OptionChainService optionChainService;

    private static final String UNDERLYING = "NIFTY";
    private static final LocalDate EXPIRY = LocalDate.now().plusDays(7);

    @BeforeEach
    void setUp() {
        greeksCalculator = new GreeksCalculator(new IVCalculator());
        optionChainService = new OptionChainService(instrumentService, kiteConnect, greeksCalculator);
    }

    @Nested
    @DisplayName("Option Chain Building")
    class ChainBuilding {

        @Test
        @DisplayName("Builds option chain with CE and PE at each strike")
        void buildsChainWithBothSides() throws Throwable {
            // Setup instruments
            List<Instrument> instruments = List.of(
                    createInstrument(1001L, "NIFTY24FEB22000CE", InstrumentType.CE, 22000),
                    createInstrument(1002L, "NIFTY24FEB22000PE", InstrumentType.PE, 22000),
                    createInstrument(1003L, "NIFTY24FEB22100CE", InstrumentType.CE, 22100),
                    createInstrument(1004L, "NIFTY24FEB22100PE", InstrumentType.PE, 22100));

            when(instrumentService.getOptionsForUnderlying(UNDERLYING, EXPIRY)).thenReturn(instruments);

            // Setup quotes
            Map<String, Quote> quotes = Map.of(
                    "NFO:NIFTY24FEB22000CE", createQuote(250, 100000, 1500000),
                    "NFO:NIFTY24FEB22000PE", createQuote(200, 80000, 1200000),
                    "NFO:NIFTY24FEB22100CE", createQuote(180, 90000, 1100000),
                    "NFO:NIFTY24FEB22100PE", createQuote(270, 85000, 1300000));

            when(kiteConnect.getQuote(any(String[].class))).thenReturn(quotes);

            // Setup spot price quote
            Quote spotQuote = createQuote(22050, 0, 0);
            when(kiteConnect.getQuote(new String[] {"NSE:NIFTY"})).thenReturn(Map.of("NSE:NIFTY", spotQuote));

            OptionChain chain = optionChainService.getOptionChain(UNDERLYING, EXPIRY);

            assertNotNull(chain);
            assertEquals(UNDERLYING, chain.getUnderlying());
            assertEquals(EXPIRY, chain.getExpiry());
            assertEquals(2, chain.getEntries().size(), "Should have 2 strike levels");

            // Verify first entry (strike 22000)
            OptionChainEntry entry22000 = chain.getEntries().get(0);
            assertEquals(
                    BigDecimal.valueOf(22000).setScale(1),
                    entry22000.getStrike().setScale(1));
            assertNotNull(entry22000.getCall(), "Should have call side");
            assertNotNull(entry22000.getPut(), "Should have put side");
            assertEquals("CE", entry22000.getCall().getOptionType());
            assertEquals("PE", entry22000.getPut().getOptionType());
        }

        @Test
        @DisplayName("Chain entries are ordered by strike ascending")
        void chainEntriesOrderedByStrike() throws Throwable {
            List<Instrument> instruments = List.of(
                    createInstrument(1005L, "NIFTY24FEB22200CE", InstrumentType.CE, 22200),
                    createInstrument(1001L, "NIFTY24FEB22000CE", InstrumentType.CE, 22000),
                    createInstrument(1003L, "NIFTY24FEB22100CE", InstrumentType.CE, 22100));

            when(instrumentService.getOptionsForUnderlying(UNDERLYING, EXPIRY)).thenReturn(instruments);
            when(kiteConnect.getQuote(any(String[].class))).thenReturn(createQuoteMap(instruments));

            Quote spotQuote = createQuote(22050, 0, 0);
            when(kiteConnect.getQuote(new String[] {"NSE:NIFTY"})).thenReturn(Map.of("NSE:NIFTY", spotQuote));

            OptionChain chain = optionChainService.getOptionChain(UNDERLYING, EXPIRY);

            List<BigDecimal> strikes =
                    chain.getEntries().stream().map(OptionChainEntry::getStrike).toList();

            for (int i = 1; i < strikes.size(); i++) {
                assertTrue(strikes.get(i).compareTo(strikes.get(i - 1)) > 0, "Strikes should be ascending: " + strikes);
            }
        }

        @Test
        @DisplayName("Empty instruments throws IllegalArgumentException")
        void emptyInstrumentsThrows() {
            when(instrumentService.getOptionsForUnderlying(UNDERLYING, EXPIRY)).thenReturn(List.of());

            assertThrows(IllegalArgumentException.class, () -> optionChainService.getOptionChain(UNDERLYING, EXPIRY));
        }

        @Test
        @DisplayName("Option data includes Greeks")
        void optionDataIncludesGreeks() throws Throwable {
            List<Instrument> instruments =
                    List.of(createInstrument(1001L, "NIFTY24FEB22000CE", InstrumentType.CE, 22000));

            when(instrumentService.getOptionsForUnderlying(UNDERLYING, EXPIRY)).thenReturn(instruments);

            Map<String, Quote> quotes = Map.of("NFO:NIFTY24FEB22000CE", createQuote(250, 100000, 1500000));
            when(kiteConnect.getQuote(any(String[].class))).thenReturn(quotes);

            Quote spotQuote = createQuote(22000, 0, 0);
            when(kiteConnect.getQuote(new String[] {"NSE:NIFTY"})).thenReturn(Map.of("NSE:NIFTY", spotQuote));

            OptionChain chain = optionChainService.getOptionChain(UNDERLYING, EXPIRY);

            OptionData callData = chain.getEntries().get(0).getCall();
            assertNotNull(callData.getGreeks(), "Greeks should be calculated");
        }
    }

    @Nested
    @DisplayName("Cache Behavior")
    class CacheBehavior {

        @Test
        @DisplayName("Second call returns cached chain without hitting Kite API again")
        void cachedChainReturned() throws Throwable {
            List<Instrument> instruments =
                    List.of(createInstrument(1001L, "NIFTY24FEB22000CE", InstrumentType.CE, 22000));

            when(instrumentService.getOptionsForUnderlying(UNDERLYING, EXPIRY)).thenReturn(instruments);

            Map<String, Quote> quotes = Map.of("NFO:NIFTY24FEB22000CE", createQuote(250, 100000, 1500000));
            when(kiteConnect.getQuote(any(String[].class))).thenReturn(quotes);

            Quote spotQuote = createQuote(22000, 0, 0);
            when(kiteConnect.getQuote(new String[] {"NSE:NIFTY"})).thenReturn(Map.of("NSE:NIFTY", spotQuote));

            // First call — builds and caches
            OptionChain chain1 = optionChainService.getOptionChain(UNDERLYING, EXPIRY);
            // Second call — should use cache
            OptionChain chain2 = optionChainService.getOptionChain(UNDERLYING, EXPIRY);

            assertSame(chain1, chain2, "Second call should return cached instance");
            // getQuote should be called only during the first build (twice: once for instruments, once for spot)
            verify(kiteConnect, atMost(2)).getQuote(any(String[].class));
        }

        @Test
        @DisplayName("Cache invalidation forces fresh fetch")
        void cacheInvalidationForcesRefresh() throws Throwable {
            List<Instrument> instruments =
                    List.of(createInstrument(1001L, "NIFTY24FEB22000CE", InstrumentType.CE, 22000));

            when(instrumentService.getOptionsForUnderlying(UNDERLYING, EXPIRY)).thenReturn(instruments);

            Map<String, Quote> quotes = Map.of("NFO:NIFTY24FEB22000CE", createQuote(250, 100000, 1500000));
            when(kiteConnect.getQuote(any(String[].class))).thenReturn(quotes);

            Quote spotQuote = createQuote(22000, 0, 0);
            when(kiteConnect.getQuote(new String[] {"NSE:NIFTY"})).thenReturn(Map.of("NSE:NIFTY", spotQuote));

            // First call
            optionChainService.getOptionChain(UNDERLYING, EXPIRY);
            // Invalidate
            optionChainService.invalidateCache(UNDERLYING, EXPIRY);
            // Second call — should rebuild
            optionChainService.getOptionChain(UNDERLYING, EXPIRY);

            // InstrumentService should be called twice (once per build)
            verify(instrumentService, times(2)).getOptionsForUnderlying(UNDERLYING, EXPIRY);
        }
    }

    @Nested
    @DisplayName("ATM Strike Selection")
    class AtmStrikeSelection {

        @Test
        @DisplayName("Finds exact ATM strike when spot matches a strike")
        void findsExactAtm() {
            BigDecimal atm = optionChainService.findATMStrike(
                    BigDecimal.valueOf(22000),
                    List.of(BigDecimal.valueOf(21900), BigDecimal.valueOf(22000), BigDecimal.valueOf(22100)));

            assertEquals(BigDecimal.valueOf(22000), atm);
        }

        @Test
        @DisplayName("Finds nearest strike when spot is between strikes")
        void findsNearestStrike() {
            BigDecimal atm = optionChainService.findATMStrike(
                    BigDecimal.valueOf(22040),
                    List.of(BigDecimal.valueOf(21900), BigDecimal.valueOf(22000), BigDecimal.valueOf(22100)));

            assertEquals(BigDecimal.valueOf(22000), atm, "22040 is closer to 22000 than 22100");
        }

        @Test
        @DisplayName("Returns null for empty strikes")
        void returnsNullForEmptyStrikes() {
            BigDecimal atm = optionChainService.findATMStrike(BigDecimal.valueOf(22000), List.of());
            assertNull(atm);
        }
    }

    @Nested
    @DisplayName("Strike Offset Selection")
    class StrikeOffsetSelection {

        @Test
        @DisplayName("Positive offset returns higher strike")
        void positiveOffsetReturnsHigherStrike() {
            OptionChain chain = createMockChain(22000, 21900, 22000, 22100, 22200);

            BigDecimal strike = optionChainService.getStrikeByOffset(chain, 1);
            assertNotNull(strike);
            assertEquals(0, BigDecimal.valueOf(22100).compareTo(strike), "Should return strike 22100");
        }

        @Test
        @DisplayName("Negative offset returns lower strike")
        void negativeOffsetReturnsLowerStrike() {
            OptionChain chain = createMockChain(22000, 21900, 22000, 22100, 22200);

            BigDecimal strike = optionChainService.getStrikeByOffset(chain, -1);
            assertNotNull(strike);
            assertEquals(0, BigDecimal.valueOf(21900).compareTo(strike), "Should return strike 21900");
        }

        @Test
        @DisplayName("Out of range offset returns null")
        void outOfRangeReturnsNull() {
            OptionChain chain = createMockChain(22000, 21900, 22000, 22100);

            BigDecimal strike = optionChainService.getStrikeByOffset(chain, 10);
            assertNull(strike);
        }

        @Test
        @DisplayName("Zero offset returns ATM strike")
        void zeroOffsetReturnsAtm() {
            OptionChain chain = createMockChain(22000, 21900, 22000, 22100);

            BigDecimal strike = optionChainService.getStrikeByOffset(chain, 0);
            assertNotNull(strike);
            assertEquals(0, BigDecimal.valueOf(22000).compareTo(strike), "Should return ATM strike");
        }
    }

    @Nested
    @DisplayName("Delta-Based Strike Selection")
    class DeltaBasedSelection {

        @Test
        @DisplayName("findByDelta returns option closest to target delta")
        void findsByDelta() {
            OptionChain chain = createChainWithGreeks();

            OptionData result = optionChainService.findByDelta(chain, 0.25, true);

            assertNotNull(result, "Should find a call near 0.25 delta");
        }

        @Test
        @DisplayName("findByDelta returns null for empty chain")
        void returnsNullForEmptyChain() {
            OptionChain chain = OptionChain.builder()
                    .underlying(UNDERLYING)
                    .spotPrice(BigDecimal.valueOf(22000))
                    .expiry(EXPIRY)
                    .entries(List.of())
                    .build();

            OptionData result = optionChainService.findByDelta(chain, 0.25, true);
            assertNull(result);
        }
    }

    // ---- Test helpers ----

    private Instrument createInstrument(Long token, String tradingSymbol, InstrumentType type, double strike) {
        return Instrument.builder()
                .token(token)
                .tradingSymbol(tradingSymbol)
                .name(UNDERLYING)
                .underlying(UNDERLYING)
                .exchange("NFO")
                .segment("NFO-OPT")
                .type(type)
                .strike(BigDecimal.valueOf(strike))
                .expiry(EXPIRY)
                .lotSize(75)
                .tickSize(BigDecimal.valueOf(0.05))
                .downloadDate(LocalDate.now())
                .build();
    }

    private Quote createQuote(double ltp, double oi, double volume) {
        Quote quote = new Quote();
        quote.lastPrice = ltp;
        quote.oi = oi;
        quote.volumeTradedToday = volume;
        quote.change = ltp * 0.01; // 1% change
        quote.depth = new MarketDepth();
        quote.depth.buy = new ArrayList<>();
        quote.depth.sell = new ArrayList<>();
        Depth bid = new Depth();
        bid.setPrice(ltp - 0.5);
        bid.setQuantity(100);
        bid.setOrders(5);
        quote.depth.buy.add(bid);
        Depth ask = new Depth();
        ask.setPrice(ltp + 0.5);
        ask.setQuantity(80);
        ask.setOrders(4);
        quote.depth.sell.add(ask);
        return quote;
    }

    private Map<String, Quote> createQuoteMap(List<Instrument> instruments) {
        Map<String, Quote> quotes = new java.util.HashMap<>();
        for (Instrument inst : instruments) {
            quotes.put("NFO:" + inst.getTradingSymbol(), createQuote(250, 100000, 1500000));
        }
        return quotes;
    }

    private OptionChain createMockChain(double atmStrike, double... strikes) {
        List<OptionChainEntry> entries = new ArrayList<>();
        for (double s : strikes) {
            entries.add(new OptionChainEntry(BigDecimal.valueOf(s)));
        }
        return OptionChain.builder()
                .underlying(UNDERLYING)
                .spotPrice(BigDecimal.valueOf(atmStrike))
                .expiry(EXPIRY)
                .atmStrike(BigDecimal.valueOf(atmStrike))
                .entries(entries)
                .build();
    }

    private OptionChain createChainWithGreeks() {
        List<OptionChainEntry> entries = new ArrayList<>();

        // Create entries with mock Greeks at different deltas
        double[] strikes = {21800, 21900, 22000, 22100, 22200};
        double[] callDeltas = {0.80, 0.65, 0.50, 0.35, 0.20};

        for (int i = 0; i < strikes.length; i++) {
            OptionChainEntry entry = new OptionChainEntry(BigDecimal.valueOf(strikes[i]));

            Greeks callGreeks = Greeks.builder()
                    .delta(BigDecimal.valueOf(callDeltas[i]))
                    .gamma(BigDecimal.valueOf(0.0001))
                    .theta(BigDecimal.valueOf(-5.0))
                    .vega(BigDecimal.valueOf(10.0))
                    .rho(BigDecimal.valueOf(0.05))
                    .iv(BigDecimal.valueOf(15.0))
                    .build();

            OptionData callData = OptionData.builder()
                    .instrumentToken((long) (1000 + i))
                    .tradingSymbol("NIFTY24FEB" + (int) strikes[i] + "CE")
                    .strike(BigDecimal.valueOf(strikes[i]))
                    .optionType("CE")
                    .ltp(BigDecimal.valueOf(200 - i * 40))
                    .greeks(callGreeks)
                    .build();

            entry.setCall(callData);
            entries.add(entry);
        }

        return OptionChain.builder()
                .underlying(UNDERLYING)
                .spotPrice(BigDecimal.valueOf(22000))
                .expiry(EXPIRY)
                .atmStrike(BigDecimal.valueOf(22000))
                .entries(entries)
                .build();
    }
}
