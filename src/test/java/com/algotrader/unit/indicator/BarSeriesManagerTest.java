package com.algotrader.unit.indicator;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.indicator.BarSeriesManager;
import com.algotrader.indicator.InstrumentIndicatorConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for BarSeriesManager: tick-to-bar aggregation, historical bars, and snapshot retrieval.
 */
class BarSeriesManagerTest {

    private static final Long TOKEN = 256265L;
    private static final String SYMBOL = "NIFTY";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2025, 2, 10, 9, 15, 0);

    private BarSeriesManager barSeriesManager;

    @BeforeEach
    void setUp() {
        InstrumentIndicatorConfig config = new InstrumentIndicatorConfig();
        config.setInstrumentToken(TOKEN);
        config.setTradingSymbol(SYMBOL);
        config.setBarDuration(Duration.ofMinutes(1));
        config.setMaxBars(100);

        barSeriesManager = new BarSeriesManager(config);
    }

    @Nested
    @DisplayName("Configuration")
    class Configuration {

        @Test
        @DisplayName("stores instrument token and symbol from config")
        void storesConfigValues() {
            assertThat(barSeriesManager.getInstrumentToken()).isEqualTo(TOKEN);
            assertThat(barSeriesManager.getTradingSymbol()).isEqualTo(SYMBOL);
            assertThat(barSeriesManager.getBarDuration()).isEqualTo(Duration.ofMinutes(1));
        }

        @Test
        @DisplayName("starts with zero bars")
        void startsWithZeroBars() {
            assertThat(barSeriesManager.getBarCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Tick processing")
    class TickProcessing {

        @Test
        @DisplayName("tick within bar duration does not complete a bar")
        void tickWithinDurationDoesNotCompleteBar() {
            boolean completed = barSeriesManager.processTick(BigDecimal.valueOf(22500), 1000, BASE_TIME);

            assertThat(completed).isFalse();
            assertThat(barSeriesManager.getBarCount()).isZero();
        }

        @Test
        @DisplayName("tick at bar duration boundary completes a bar")
        void tickAtBoundaryCompletesBar() {
            barSeriesManager.processTick(BigDecimal.valueOf(22500), 1000, BASE_TIME);
            boolean completed = barSeriesManager.processTick(BigDecimal.valueOf(22550), 2000, BASE_TIME.plusMinutes(1));

            assertThat(completed).isTrue();
            assertThat(barSeriesManager.getBarCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("multiple bars accumulate correctly")
        void multipleBarsAccumulate() {
            // Bar 1: 09:15:00 - 09:16:00
            barSeriesManager.processTick(BigDecimal.valueOf(22500), 1000, BASE_TIME);
            barSeriesManager.processTick(BigDecimal.valueOf(22550), 2000, BASE_TIME.plusSeconds(30));
            barSeriesManager.processTick(BigDecimal.valueOf(22520), 1500, BASE_TIME.plusMinutes(1));

            // Bar 2: 09:16:00 - 09:17:00
            barSeriesManager.processTick(
                    BigDecimal.valueOf(22530), 800, BASE_TIME.plusMinutes(1).plusSeconds(30));
            barSeriesManager.processTick(BigDecimal.valueOf(22560), 1200, BASE_TIME.plusMinutes(2));

            assertThat(barSeriesManager.getBarCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Historical bars")
    class HistoricalBars {

        @Test
        @DisplayName("addHistoricalBar adds a bar to the series")
        void addHistoricalBarAddsBar() {
            barSeriesManager.addHistoricalBar(
                    BASE_TIME,
                    BigDecimal.valueOf(22400),
                    BigDecimal.valueOf(22600),
                    BigDecimal.valueOf(22350),
                    BigDecimal.valueOf(22550),
                    50000);

            assertThat(barSeriesManager.getBarCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("multiple historical bars maintain order")
        void multipleHistoricalBarsInOrder() {
            for (int i = 0; i < 5; i++) {
                barSeriesManager.addHistoricalBar(
                        BASE_TIME.plusMinutes(i),
                        BigDecimal.valueOf(22400 + i * 10),
                        BigDecimal.valueOf(22500 + i * 10),
                        BigDecimal.valueOf(22350 + i * 10),
                        BigDecimal.valueOf(22450 + i * 10),
                        10000 + i * 1000);
            }

            assertThat(barSeriesManager.getBarCount()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("Bar snapshots")
    class BarSnapshots {

        @Test
        @DisplayName("empty series returns empty snapshot list")
        void emptySeriesReturnsEmptyList() {
            List<BarSeriesManager.BarSnapshot> snapshots = barSeriesManager.getBarSnapshots(10);

            assertThat(snapshots).isEmpty();
        }

        @Test
        @DisplayName("returns bar data in correct OHLCV format")
        void returnsBarDataInCorrectFormat() {
            barSeriesManager.addHistoricalBar(
                    BASE_TIME,
                    BigDecimal.valueOf(22400),
                    BigDecimal.valueOf(22600),
                    BigDecimal.valueOf(22350),
                    BigDecimal.valueOf(22550),
                    50000);

            List<BarSeriesManager.BarSnapshot> snapshots = barSeriesManager.getBarSnapshots(10);

            assertThat(snapshots).hasSize(1);
            BarSeriesManager.BarSnapshot snapshot = snapshots.getFirst();
            assertThat(snapshot.open().doubleValue()).isEqualTo(22400.0);
            assertThat(snapshot.high().doubleValue()).isEqualTo(22600.0);
            assertThat(snapshot.low().doubleValue()).isEqualTo(22350.0);
            assertThat(snapshot.close().doubleValue()).isEqualTo(22550.0);
            assertThat(snapshot.volume()).isEqualTo(50000);
        }

        @Test
        @DisplayName("maxBars limits the number of returned bars")
        void maxBarsLimitsResult() {
            for (int i = 0; i < 10; i++) {
                barSeriesManager.addHistoricalBar(
                        BASE_TIME.plusMinutes(i),
                        BigDecimal.valueOf(22400),
                        BigDecimal.valueOf(22500),
                        BigDecimal.valueOf(22350),
                        BigDecimal.valueOf(22450),
                        10000);
            }

            List<BarSeriesManager.BarSnapshot> snapshots = barSeriesManager.getBarSnapshots(5);

            assertThat(snapshots).hasSize(5);
        }

        @Test
        @DisplayName("returns most recent bars when limited")
        void returnsMostRecentBarsWhenLimited() {
            for (int i = 0; i < 5; i++) {
                barSeriesManager.addHistoricalBar(
                        BASE_TIME.plusMinutes(i),
                        BigDecimal.valueOf(22400 + i * 100),
                        BigDecimal.valueOf(22500 + i * 100),
                        BigDecimal.valueOf(22350 + i * 100),
                        BigDecimal.valueOf(22450 + i * 100),
                        10000);
            }

            List<BarSeriesManager.BarSnapshot> snapshots = barSeriesManager.getBarSnapshots(2);

            assertThat(snapshots).hasSize(2);
            // Last bar's open should be 22400 + 4*100 = 22800
            assertThat(snapshots.getLast().open().doubleValue()).isEqualTo(22800.0);
        }
    }

    @Nested
    @DisplayName("Lock access")
    class LockAccess {

        @Test
        @DisplayName("read and write locks are accessible")
        void locksAreAccessible() {
            assertThat(barSeriesManager.getReadLock()).isNotNull();
            assertThat(barSeriesManager.getWriteLock()).isNotNull();
        }
    }
}
