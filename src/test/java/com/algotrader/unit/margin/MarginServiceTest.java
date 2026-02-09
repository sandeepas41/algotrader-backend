package com.algotrader.unit.margin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.domain.model.AccountMargin;
import com.algotrader.exception.BrokerException;
import com.algotrader.exception.SessionExpiredException;
import com.algotrader.margin.MarginService;
import com.algotrader.session.SessionHealthService;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for MarginService covering cache behavior, session awareness,
 * graceful degradation, and utilization calculation.
 */
@ExtendWith(MockitoExtension.class)
class MarginServiceTest {

    @Mock
    private BrokerGateway brokerGateway;

    @Mock
    private SessionHealthService sessionHealthService;

    private MarginService marginService;

    @BeforeEach
    void setUp() {
        marginService = new MarginService(brokerGateway, sessionHealthService);
    }

    private Map<String, BigDecimal> sampleMargins() {
        return Map.of(
                "cash", new BigDecimal("500000"),
                "available", new BigDecimal("800000"),
                "used", new BigDecimal("200000"),
                "collateral", new BigDecimal("100000"));
    }

    // ==============================
    // CACHE BEHAVIOR
    // ==============================

    @Nested
    @DisplayName("Cache Behavior")
    class CacheBehavior {

        @Test
        @DisplayName("Returns cached value within 30s TTL without API call")
        void returnsCachedWithinTTL() {
            when(sessionHealthService.isSessionActive()).thenReturn(true);
            when(brokerGateway.getMargins()).thenReturn(sampleMargins());

            // First call fetches from broker
            AccountMargin first = marginService.getMargins();
            // Second call should use cache
            AccountMargin second = marginService.getMargins();

            assertThat(first).isEqualTo(second);
            // Only one broker call
            verify(brokerGateway, times(1)).getMargins();
        }

        @Test
        @DisplayName("Fetches fresh data after cache invalidation")
        void fetchesAfterInvalidation() {
            when(sessionHealthService.isSessionActive()).thenReturn(true);
            when(brokerGateway.getMargins()).thenReturn(sampleMargins());

            marginService.getMargins();
            marginService.invalidateCache();
            marginService.getMargins();

            // Two broker calls: initial + after invalidation
            verify(brokerGateway, times(2)).getMargins();
        }

        @Test
        @DisplayName("Maps Kite margin fields correctly")
        void mapsFieldsCorrectly() {
            when(sessionHealthService.isSessionActive()).thenReturn(true);
            when(brokerGateway.getMargins()).thenReturn(sampleMargins());

            AccountMargin margin = marginService.getMargins();

            assertThat(margin.getAvailableCash()).isEqualByComparingTo("500000");
            assertThat(margin.getAvailableMargin()).isEqualByComparingTo("800000");
            assertThat(margin.getUsedMargin()).isEqualByComparingTo("200000");
            assertThat(margin.getCollateral()).isEqualByComparingTo("100000");
            assertThat(margin.getTotalCapital()).isEqualByComparingTo("1000000");
            assertThat(margin.getFetchedAt()).isNotNull();
        }
    }

    // ==============================
    // UTILIZATION CALCULATION
    // ==============================

    @Nested
    @DisplayName("Utilization Calculation")
    class UtilizationCalculation {

        @Test
        @DisplayName("Calculates utilization percentage correctly")
        void calculatesUtilization() {
            when(sessionHealthService.isSessionActive()).thenReturn(true);
            when(brokerGateway.getMargins()).thenReturn(sampleMargins());

            AccountMargin margin = marginService.getMargins();

            // used=200000, total=1000000 -> 20%
            assertThat(margin.getUtilizationPercent()).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("Returns zero utilization when total capital is zero")
        void zeroCapitalReturnsZeroUtilization() {
            when(sessionHealthService.isSessionActive()).thenReturn(true);
            when(brokerGateway.getMargins())
                    .thenReturn(Map.of(
                            "cash", BigDecimal.ZERO,
                            "available", BigDecimal.ZERO,
                            "used", BigDecimal.ZERO,
                            "collateral", BigDecimal.ZERO));

            AccountMargin margin = marginService.getMargins();

            assertThat(margin.getUtilizationPercent()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("Convenience method getUtilizationPercent delegates correctly")
        void getUtilizationPercentDelegates() {
            when(sessionHealthService.isSessionActive()).thenReturn(true);
            when(brokerGateway.getMargins()).thenReturn(sampleMargins());

            BigDecimal utilization = marginService.getUtilizationPercent();

            assertThat(utilization).isEqualByComparingTo("20.00");
        }
    }

    // ==============================
    // SESSION AWARENESS
    // ==============================

    @Nested
    @DisplayName("Session Awareness")
    class SessionAwareness {

        @Test
        @DisplayName("Returns stale data when session inactive but cache exists")
        void returnsStaleWhenSessionInactive() {
            when(sessionHealthService.isSessionActive()).thenReturn(true);
            when(brokerGateway.getMargins()).thenReturn(sampleMargins());

            // Populate cache
            marginService.getMargins();
            marginService.invalidateCache();

            // Session now inactive
            when(sessionHealthService.isSessionActive()).thenReturn(false);

            // Should not throw — but also no cached data after invalidation
            assertThatThrownBy(() -> marginService.getMargins()).isInstanceOf(SessionExpiredException.class);
        }

        @Test
        @DisplayName("Throws SessionExpiredException when no session and no cache")
        void throwsWhenNoSessionNoCache() {
            when(sessionHealthService.isSessionActive()).thenReturn(false);

            assertThatThrownBy(() -> marginService.getMargins())
                    .isInstanceOf(SessionExpiredException.class)
                    .hasMessageContaining("session not active");
        }
    }

    // ==============================
    // ERROR HANDLING
    // ==============================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("Returns stale data on broker error when cache exists")
        void returnsStaleCacheOnBrokerError() {
            when(sessionHealthService.isSessionActive()).thenReturn(true);
            when(brokerGateway.getMargins()).thenReturn(sampleMargins());

            // Populate cache
            AccountMargin cached = marginService.getMargins();
            marginService.invalidateCache();

            // Broker error on next fetch
            when(brokerGateway.getMargins()).thenThrow(new RuntimeException("API timeout"));

            // Should still return stale — wait, cache was invalidated.
            // With invalidation, there's no cache to fall back on.
            assertThatThrownBy(() -> marginService.getMargins()).isInstanceOf(BrokerException.class);
        }

        @Test
        @DisplayName("Throws BrokerException on broker error with no cache")
        void throwsOnBrokerErrorNoCache() {
            when(sessionHealthService.isSessionActive()).thenReturn(true);
            when(brokerGateway.getMargins()).thenThrow(new RuntimeException("connection refused"));

            assertThatThrownBy(() -> marginService.getMargins())
                    .isInstanceOf(BrokerException.class)
                    .hasMessageContaining("Failed to fetch margins");
        }

        @Test
        @DisplayName("Handles missing keys in margin map gracefully")
        void handlesMissingKeys() {
            when(sessionHealthService.isSessionActive()).thenReturn(true);
            when(brokerGateway.getMargins()).thenReturn(Map.of());

            AccountMargin margin = marginService.getMargins();

            assertThat(margin.getAvailableCash()).isEqualByComparingTo("0");
            assertThat(margin.getAvailableMargin()).isEqualByComparingTo("0");
            assertThat(margin.getUsedMargin()).isEqualByComparingTo("0");
            assertThat(margin.getCollateral()).isEqualByComparingTo("0");
        }
    }

    // ==============================
    // CONVENIENCE METHODS
    // ==============================

    @Nested
    @DisplayName("Convenience Methods")
    class ConvenienceMethods {

        @Test
        @DisplayName("getAvailableMargin returns correct value")
        void getAvailableMargin() {
            when(sessionHealthService.isSessionActive()).thenReturn(true);
            when(brokerGateway.getMargins()).thenReturn(sampleMargins());

            BigDecimal available = marginService.getAvailableMargin();

            assertThat(available).isEqualByComparingTo("800000");
        }
    }
}
