package com.algotrader.unit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.broker.InstrumentSubscriptionManager;
import com.algotrader.broker.KiteMarketDataService;
import com.algotrader.domain.enums.ExpiryType;
import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.enums.SubscriptionPriority;
import com.algotrader.domain.model.Instrument;
import com.algotrader.domain.model.WatchlistConfig;
import com.algotrader.service.InstrumentService;
import com.algotrader.service.WatchlistConfigService;
import com.algotrader.service.WatchlistSubscriptionService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link WatchlistSubscriptionService}.
 * Tests startup auto-subscription logic including expiry resolution and token subscription.
 */
@ExtendWith(MockitoExtension.class)
class WatchlistSubscriptionServiceTest {

    @Mock
    private WatchlistConfigService watchlistConfigService;

    @Mock
    private InstrumentService instrumentService;

    @Mock
    private InstrumentSubscriptionManager instrumentSubscriptionManager;

    @Mock
    private KiteMarketDataService kiteMarketDataService;

    private WatchlistSubscriptionService watchlistSubscriptionService;

    @BeforeEach
    void setUp() {
        watchlistSubscriptionService = new WatchlistSubscriptionService(
                watchlistConfigService, instrumentService, instrumentSubscriptionManager, kiteMarketDataService);
    }

    @Test
    @DisplayName("subscribeAll: subscribes spot + FUT tokens for enabled configs")
    void subscribeAllSubscribesSpotAndFut() {
        LocalDate today = LocalDate.now();
        LocalDate nearestExpiry = today.plusDays(3);

        WatchlistConfig config = WatchlistConfig.builder()
                .underlying("NIFTY")
                .strikesFromAtm(10)
                .expiryType(ExpiryType.NEAREST_WEEKLY)
                .enabled(true)
                .build();

        when(watchlistConfigService.getEnabledConfigs()).thenReturn(List.of(config));

        // Spot instrument
        Instrument spot = Instrument.builder()
                .token(256265L)
                .tradingSymbol("NIFTY 50")
                .exchange("NSE")
                .type(InstrumentType.EQ)
                .build();
        when(instrumentService.getSpotInstrument("NIFTY")).thenReturn(Optional.of(spot));

        // Expiries
        when(instrumentService.getExpiries("NIFTY")).thenReturn(List.of(nearestExpiry));

        // FUT for that expiry
        Instrument fut = Instrument.builder()
                .token(9001L)
                .tradingSymbol("NIFTY26FEBFUT")
                .exchange("NFO")
                .type(InstrumentType.FUT)
                .expiry(nearestExpiry)
                .build();
        when(instrumentService.getDerivativesForExpiry("NIFTY", nearestExpiry)).thenReturn(List.of(fut));

        // Subscription manager returns the tokens as new
        List<Long> expectedTokens = List.of(256265L, 9001L);
        when(instrumentSubscriptionManager.subscribe(
                        eq("watchlist:NIFTY"), eq(expectedTokens), eq(SubscriptionPriority.MANUAL)))
                .thenReturn(expectedTokens);

        watchlistSubscriptionService.subscribeAll();

        // Verify subscription manager was called with spot + FUT tokens
        verify(instrumentSubscriptionManager).subscribe("watchlist:NIFTY", expectedTokens, SubscriptionPriority.MANUAL);

        // Verify actual WebSocket subscription
        verify(kiteMarketDataService).subscribe(expectedTokens);
    }

    @Test
    @DisplayName("subscribeAll: skips when no enabled configs")
    void subscribeAllSkipsWhenNoConfigs() {
        when(watchlistConfigService.getEnabledConfigs()).thenReturn(List.of());

        watchlistSubscriptionService.subscribeAll();

        verify(instrumentSubscriptionManager, never()).subscribe(any(), any(), any());
        verify(kiteMarketDataService, never()).subscribe(any());
    }

    @Test
    @DisplayName("subscribeAll: handles missing spot instrument gracefully")
    void subscribeAllHandlesMissingSpot() {
        LocalDate nearestExpiry = LocalDate.now().plusDays(3);

        WatchlistConfig config = WatchlistConfig.builder()
                .underlying("UNKNOWNSYM")
                .strikesFromAtm(10)
                .expiryType(ExpiryType.NEAREST_WEEKLY)
                .enabled(true)
                .build();

        when(watchlistConfigService.getEnabledConfigs()).thenReturn(List.of(config));
        when(instrumentService.getSpotInstrument("UNKNOWNSYM")).thenReturn(Optional.empty());
        when(instrumentService.getExpiries("UNKNOWNSYM")).thenReturn(List.of(nearestExpiry));
        when(instrumentService.getDerivativesForExpiry("UNKNOWNSYM", nearestExpiry))
                .thenReturn(List.of());

        watchlistSubscriptionService.subscribeAll();

        // No tokens to subscribe, so kiteMarketDataService should not be called
        verify(kiteMarketDataService, never()).subscribe(any());
    }

    @Test
    @DisplayName("subscribeAll: selects nearest monthly expiry correctly")
    void subscribeAllSelectsMonthlyExpiry() {
        // Use fixed dates in the same month to ensure predictable monthly resolution
        LocalDate weekly1 = LocalDate.of(2026, 3, 5); // Thu
        LocalDate weekly2 = LocalDate.of(2026, 3, 12); // Thu
        LocalDate monthEnd = LocalDate.of(2026, 3, 26); // Last Thu of March = monthly expiry

        WatchlistConfig config = WatchlistConfig.builder()
                .underlying("NIFTY")
                .strikesFromAtm(10)
                .expiryType(ExpiryType.NEAREST_MONTHLY)
                .enabled(true)
                .build();

        when(watchlistConfigService.getEnabledConfigs()).thenReturn(List.of(config));

        Instrument spot = Instrument.builder()
                .token(256265L)
                .tradingSymbol("NIFTY 50")
                .type(InstrumentType.EQ)
                .build();
        when(instrumentService.getSpotInstrument("NIFTY")).thenReturn(Optional.of(spot));

        // All expiries in March — monthly = the last one (Mar 26)
        when(instrumentService.getExpiries("NIFTY")).thenReturn(List.of(weekly1, weekly2, monthEnd));

        // Return empty derivatives to simplify — just testing expiry resolution
        when(instrumentService.getDerivativesForExpiry("NIFTY", monthEnd)).thenReturn(List.of());

        // Expect only spot token (no FUT found for monthly expiry)
        List<Long> expectedTokens = List.of(256265L);
        when(instrumentSubscriptionManager.subscribe(
                        eq("watchlist:NIFTY"), eq(expectedTokens), eq(SubscriptionPriority.MANUAL)))
                .thenReturn(expectedTokens);

        watchlistSubscriptionService.subscribeAll();

        // Verify it resolved to the monthly (last) expiry
        verify(instrumentService).getDerivativesForExpiry("NIFTY", monthEnd);
    }
}
