package com.algotrader.unit.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.algotrader.domain.model.Tick;
import com.algotrader.event.IndicatorUpdateEvent;
import com.algotrader.event.TickEvent;
import com.algotrader.indicator.HistoricalDataSeeder;
import com.algotrader.indicator.IndicatorConfig;
import com.algotrader.indicator.IndicatorDefinition;
import com.algotrader.indicator.IndicatorService;
import com.algotrader.indicator.IndicatorType;
import com.algotrader.indicator.InstrumentIndicatorConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Tests for IndicatorService: initialization, tick processing, caching,
 * and event publishing.
 */
@ExtendWith(MockitoExtension.class)
class IndicatorServiceTest {

    private static final Long NIFTY_TOKEN = 256265L;
    private static final String NIFTY_SYMBOL = "NIFTY";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2025, 2, 10, 9, 15, 0);

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private HistoricalDataSeeder historicalDataSeeder;

    private IndicatorService indicatorService;

    @BeforeEach
    void setUp() {
        IndicatorConfig config = new IndicatorConfig();
        config.setEnabled(true);

        InstrumentIndicatorConfig niftyConfig = new InstrumentIndicatorConfig();
        niftyConfig.setInstrumentToken(NIFTY_TOKEN);
        niftyConfig.setTradingSymbol(NIFTY_SYMBOL);
        niftyConfig.setBarDuration(Duration.ofMinutes(1));
        niftyConfig.setMaxBars(100);

        IndicatorDefinition rsiDef = new IndicatorDefinition();
        rsiDef.setType(IndicatorType.RSI);
        rsiDef.setParams(new HashMap<>(Map.of("period", 14)));

        IndicatorDefinition ltpDef = new IndicatorDefinition();
        ltpDef.setType(IndicatorType.LTP);

        niftyConfig.setIndicators(List.of(rsiDef, ltpDef));
        config.setInstruments(List.of(niftyConfig));

        doNothing().when(historicalDataSeeder).seed(any(), any());

        indicatorService = new IndicatorService(config, historicalDataSeeder, applicationEventPublisher);
        indicatorService.initialize();
    }

    @Nested
    @DisplayName("Initialization")
    class Initialization {

        @Test
        @DisplayName("tracks configured instruments")
        void tracksConfiguredInstruments() {
            assertThat(indicatorService.isTracked(NIFTY_TOKEN)).isTrue();
            assertThat(indicatorService.isTracked(999999L)).isFalse();
        }

        @Test
        @DisplayName("seeds historical data on init")
        void seedsHistoricalDataOnInit() {
            verify(historicalDataSeeder).seed(any(), any());
        }

        @Test
        @DisplayName("returns tracked instruments")
        void returnsTrackedInstruments() {
            List<IndicatorService.TrackedInstrument> tracked = indicatorService.getTrackedInstruments();

            assertThat(tracked).hasSize(1);
            assertThat(tracked.getFirst().instrumentToken()).isEqualTo(NIFTY_TOKEN);
            assertThat(tracked.getFirst().tradingSymbol()).isEqualTo(NIFTY_SYMBOL);
            assertThat(tracked.getFirst().barDurationSeconds()).isEqualTo(60);
        }

        @Test
        @DisplayName("disabled config skips initialization")
        void disabledConfigSkipsInit() {
            IndicatorConfig disabledConfig = new IndicatorConfig();
            disabledConfig.setEnabled(false);

            IndicatorService disabled =
                    new IndicatorService(disabledConfig, historicalDataSeeder, applicationEventPublisher);
            disabled.initialize();

            assertThat(disabled.isTracked(NIFTY_TOKEN)).isFalse();
        }
    }

    @Nested
    @DisplayName("Tick processing")
    class TickProcessing {

        @Test
        @DisplayName("ignores ticks for non-tracked instruments")
        void ignoresNonTrackedInstrumentTicks() {
            Tick tick = Tick.builder()
                    .instrumentToken(999999L)
                    .lastPrice(BigDecimal.valueOf(100))
                    .volume(1000)
                    .timestamp(BASE_TIME)
                    .build();

            indicatorService.onTick(new TickEvent(this, tick));

            // No event should be published for unknown tokens
            verify(applicationEventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("tick within bar duration does not trigger indicator update")
        void tickWithinBarDoesNotTriggerUpdate() {
            sendTick(22500.0, 1000, BASE_TIME);

            verify(applicationEventPublisher, never()).publishEvent(any(IndicatorUpdateEvent.class));
        }

        @Test
        @DisplayName("bar completion triggers indicator recalculation and event")
        void barCompletionTriggersRecalculation() {
            // Send enough ticks to complete bars and warm up RSI (needs 14+ bars)
            for (int i = 0; i <= 15; i++) {
                double price = 22500 + (i * 10);
                sendTick(price, 1000, BASE_TIME.plusMinutes(i));
            }

            // At least some bar completions should have published events
            verify(applicationEventPublisher, times(15)).publishEvent(any(IndicatorUpdateEvent.class));
        }
    }

    @Nested
    @DisplayName("Indicator cache")
    class IndicatorCache {

        @Test
        @DisplayName("snapshot is empty before any bar completes")
        void snapshotEmptyBeforeBarComplete() {
            Map<String, BigDecimal> snapshot = indicatorService.getIndicatorSnapshot(NIFTY_TOKEN);

            assertThat(snapshot).isEmpty();
        }

        @Test
        @DisplayName("snapshot is empty for non-tracked instrument")
        void snapshotEmptyForNonTracked() {
            Map<String, BigDecimal> snapshot = indicatorService.getIndicatorSnapshot(999999L);

            assertThat(snapshot).isEmpty();
        }

        @Test
        @DisplayName("getIndicatorValue returns null for missing indicator")
        void getValueReturnsNullForMissing() {
            BigDecimal value = indicatorService.getIndicatorValue(NIFTY_TOKEN, IndicatorType.EMA, 21, null);

            assertThat(value).isNull();
        }
    }

    @Nested
    @DisplayName("Bar data")
    class BarData {

        @Test
        @DisplayName("returns empty list for non-tracked instrument")
        void returnsEmptyForNonTracked() {
            assertThat(indicatorService.getBarData(999999L, 10)).isEmpty();
        }

        @Test
        @DisplayName("returns empty list before any bar completes")
        void returnsEmptyBeforeBarComplete() {
            assertThat(indicatorService.getBarData(NIFTY_TOKEN, 10)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Active instruments (lazy calc)")
    class ActiveInstruments {

        @Test
        @DisplayName("register and unregister active instruments")
        void registerAndUnregister() {
            indicatorService.registerActiveInstrument(NIFTY_TOKEN);
            indicatorService.unregisterActiveInstrument(NIFTY_TOKEN);

            // No assertion failures -- verifies no exception thrown
        }
    }

    @Nested
    @DisplayName("Metadata")
    class Metadata {

        @Test
        @DisplayName("returns metadata for all indicator types")
        void returnsAllMetadata() {
            assertThat(indicatorService.getAvailableIndicators()).hasSize(IndicatorType.values().length);
        }
    }

    private void sendTick(double price, long volume, LocalDateTime timestamp) {
        Tick tick = Tick.builder()
                .instrumentToken(NIFTY_TOKEN)
                .lastPrice(BigDecimal.valueOf(price))
                .volume(volume)
                .timestamp(timestamp)
                .build();
        indicatorService.onTick(new TickEvent(this, tick));
    }
}
