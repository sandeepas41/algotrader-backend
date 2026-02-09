package com.algotrader.unit.margin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.domain.model.AccountMargin;
import com.algotrader.event.RiskEvent;
import com.algotrader.event.RiskLevel;
import com.algotrader.margin.MarginMonitorService;
import com.algotrader.margin.MarginService;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for MarginMonitorService covering threshold detection,
 * alert deduplication, and market hours gating.
 */
@ExtendWith(MockitoExtension.class)
class MarginMonitorServiceTest {

    @Mock
    private MarginService marginService;

    @Mock
    private TradingCalendarService tradingCalendarService;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private MarginMonitorService marginMonitorService;

    @BeforeEach
    void setUp() {
        marginMonitorService =
                new MarginMonitorService(marginService, tradingCalendarService, applicationEventPublisher);
    }

    private AccountMargin marginWithUtilization(String utilization) {
        return AccountMargin.builder()
                .availableMargin(new BigDecimal("100000"))
                .usedMargin(new BigDecimal("100000"))
                .totalCapital(new BigDecimal("200000"))
                .utilizationPercent(new BigDecimal(utilization))
                .fetchedAt(Instant.now())
                .build();
    }

    // ==============================
    // THRESHOLD DETECTION
    // ==============================

    @Nested
    @DisplayName("Threshold Detection")
    class ThresholdDetection {

        @Test
        @DisplayName("Publishes WARNING event at 80% utilization")
        void warningAt80() {
            AccountMargin margin = marginWithUtilization("82.5");

            marginMonitorService.checkMarginUtilization(margin);

            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());

            RiskEvent event = captor.getValue();
            assertThat(event.getLevel()).isEqualTo(RiskLevel.WARNING);
            assertThat(event.getMessage()).contains("82.5");
        }

        @Test
        @DisplayName("Publishes CRITICAL event at 90% utilization")
        void criticalAt90() {
            AccountMargin margin = marginWithUtilization("92.0");

            marginMonitorService.checkMarginUtilization(margin);

            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());

            RiskEvent event = captor.getValue();
            assertThat(event.getLevel()).isEqualTo(RiskLevel.CRITICAL);
            assertThat(event.getMessage()).contains("CRITICAL");
        }

        @Test
        @DisplayName("No event below 80% utilization")
        void noEventBelow80() {
            AccountMargin margin = marginWithUtilization("75.0");

            marginMonitorService.checkMarginUtilization(margin);

            verify(applicationEventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("Exactly 80% triggers WARNING")
        void exactlyAt80() {
            AccountMargin margin = marginWithUtilization("80.00");

            marginMonitorService.checkMarginUtilization(margin);

            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());

            assertThat(captor.getValue().getLevel()).isEqualTo(RiskLevel.WARNING);
        }

        @Test
        @DisplayName("Exactly 90% triggers CRITICAL (not WARNING)")
        void exactlyAt90() {
            AccountMargin margin = marginWithUtilization("90.00");

            marginMonitorService.checkMarginUtilization(margin);

            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());

            assertThat(captor.getValue().getLevel()).isEqualTo(RiskLevel.CRITICAL);
        }
    }

    // ==============================
    // ALERT DEDUPLICATION
    // ==============================

    @Nested
    @DisplayName("Alert Deduplication")
    class AlertDeduplication {

        @Test
        @DisplayName("WARNING fires only once during sustained high utilization")
        void warningFiresOnce() {
            AccountMargin margin = marginWithUtilization("85.0");

            marginMonitorService.checkMarginUtilization(margin);
            marginMonitorService.checkMarginUtilization(margin);
            marginMonitorService.checkMarginUtilization(margin);

            // Only one event published
            verify(applicationEventPublisher, times(1)).publishEvent(any(RiskEvent.class));
        }

        @Test
        @DisplayName("CRITICAL fires only once during sustained critical utilization")
        void criticalFiresOnce() {
            AccountMargin margin = marginWithUtilization("95.0");

            marginMonitorService.checkMarginUtilization(margin);
            marginMonitorService.checkMarginUtilization(margin);

            verify(applicationEventPublisher, times(1)).publishEvent(any(RiskEvent.class));
        }

        @Test
        @DisplayName("Flags reset when utilization drops below warning threshold")
        void flagsResetOnDrop() {
            // First: warning fires
            marginMonitorService.checkMarginUtilization(marginWithUtilization("85.0"));
            verify(applicationEventPublisher, times(1)).publishEvent(any(RiskEvent.class));

            // Drop below threshold: resets flags
            marginMonitorService.checkMarginUtilization(marginWithUtilization("70.0"));

            // Back above: fires again
            marginMonitorService.checkMarginUtilization(marginWithUtilization("85.0"));
            verify(applicationEventPublisher, times(2)).publishEvent(any(RiskEvent.class));
        }

        @Test
        @DisplayName("Manual resetAlertFlags allows re-firing")
        void manualResetAllowsRefiring() {
            marginMonitorService.checkMarginUtilization(marginWithUtilization("85.0"));
            verify(applicationEventPublisher, times(1)).publishEvent(any(RiskEvent.class));

            marginMonitorService.resetAlertFlags();

            marginMonitorService.checkMarginUtilization(marginWithUtilization("85.0"));
            verify(applicationEventPublisher, times(2)).publishEvent(any(RiskEvent.class));
        }
    }

    // ==============================
    // MARKET HOURS GATING
    // ==============================

    @Nested
    @DisplayName("Market Hours Gating")
    class MarketHoursGating {

        @Test
        @DisplayName("Skips check when market is closed")
        void skipsWhenMarketClosed() {
            when(tradingCalendarService.isMarketOpen()).thenReturn(false);

            marginMonitorService.checkMarginUtilization();

            verify(marginService, never()).getMargins();
        }

        @Test
        @DisplayName("Runs check when market is open")
        void runsWhenMarketOpen() {
            when(tradingCalendarService.isMarketOpen()).thenReturn(true);
            when(marginService.getMargins()).thenReturn(marginWithUtilization("50.0"));

            marginMonitorService.checkMarginUtilization();

            verify(marginService).getMargins();
        }
    }
}
