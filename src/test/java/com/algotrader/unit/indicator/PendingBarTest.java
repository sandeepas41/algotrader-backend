package com.algotrader.unit.indicator;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.model.Tick;
import com.algotrader.indicator.PendingBar;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for PendingBar tick accumulation and OHLCV tracking.
 */
class PendingBarTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2025, 2, 10, 9, 15, 0);

    @Nested
    @DisplayName("Initial state")
    class InitialState {

        @Test
        @DisplayName("new PendingBar has no data")
        void newPendingBarHasNoData() {
            PendingBar pendingBar = new PendingBar(BASE_TIME);

            assertThat(pendingBar.hasData()).isFalse();
            assertThat(pendingBar.getOpen()).isNull();
            assertThat(pendingBar.getHigh()).isNull();
            assertThat(pendingBar.getLow()).isNull();
            assertThat(pendingBar.getClose()).isNull();
            assertThat(pendingBar.getVolume()).isZero();
            assertThat(pendingBar.getOpenTime()).isEqualTo(BASE_TIME);
        }
    }

    @Nested
    @DisplayName("Single tick")
    class SingleTick {

        @Test
        @DisplayName("first tick sets all OHLC to the same price")
        void firstTickSetsOhlcToSamePrice() {
            PendingBar pendingBar = new PendingBar(BASE_TIME);

            pendingBar.update(tick(100.0, 500, BASE_TIME.plusSeconds(5)));

            assertThat(pendingBar.hasData()).isTrue();
            assertThat(pendingBar.getOpen()).isEqualByComparingTo("100.0");
            assertThat(pendingBar.getHigh()).isEqualByComparingTo("100.0");
            assertThat(pendingBar.getLow()).isEqualByComparingTo("100.0");
            assertThat(pendingBar.getClose()).isEqualByComparingTo("100.0");
            assertThat(pendingBar.getVolume()).isEqualTo(500);
            assertThat(pendingBar.getCloseTime()).isEqualTo(BASE_TIME.plusSeconds(5));
        }
    }

    @Nested
    @DisplayName("Multiple ticks")
    class MultipleTicks {

        @Test
        @DisplayName("tracks high correctly")
        void tracksHighCorrectly() {
            PendingBar pendingBar = new PendingBar(BASE_TIME);

            pendingBar.update(tick(100.0, 100, BASE_TIME.plusSeconds(1)));
            pendingBar.update(tick(105.0, 200, BASE_TIME.plusSeconds(2)));
            pendingBar.update(tick(102.0, 150, BASE_TIME.plusSeconds(3)));

            assertThat(pendingBar.getHigh()).isEqualByComparingTo("105.0");
        }

        @Test
        @DisplayName("tracks low correctly")
        void tracksLowCorrectly() {
            PendingBar pendingBar = new PendingBar(BASE_TIME);

            pendingBar.update(tick(100.0, 100, BASE_TIME.plusSeconds(1)));
            pendingBar.update(tick(95.0, 200, BASE_TIME.plusSeconds(2)));
            pendingBar.update(tick(98.0, 150, BASE_TIME.plusSeconds(3)));

            assertThat(pendingBar.getLow()).isEqualByComparingTo("95.0");
        }

        @Test
        @DisplayName("open is first tick, close is last tick")
        void openIsFirstCloseIsLast() {
            PendingBar pendingBar = new PendingBar(BASE_TIME);

            pendingBar.update(tick(100.0, 100, BASE_TIME.plusSeconds(1)));
            pendingBar.update(tick(110.0, 200, BASE_TIME.plusSeconds(30)));
            pendingBar.update(tick(107.5, 300, BASE_TIME.plusSeconds(59)));

            assertThat(pendingBar.getOpen()).isEqualByComparingTo("100.0");
            assertThat(pendingBar.getClose()).isEqualByComparingTo("107.5");
        }

        @Test
        @DisplayName("volume accumulates across ticks")
        void volumeAccumulates() {
            PendingBar pendingBar = new PendingBar(BASE_TIME);

            pendingBar.update(tick(100.0, 100, BASE_TIME.plusSeconds(1)));
            pendingBar.update(tick(101.0, 250, BASE_TIME.plusSeconds(2)));
            pendingBar.update(tick(102.0, 150, BASE_TIME.plusSeconds(3)));

            assertThat(pendingBar.getVolume()).isEqualTo(500);
        }

        @Test
        @DisplayName("closeTime tracks the latest tick timestamp")
        void closeTimeTracksLatest() {
            PendingBar pendingBar = new PendingBar(BASE_TIME);
            LocalDateTime lastTime = BASE_TIME.plusSeconds(55);

            pendingBar.update(tick(100.0, 100, BASE_TIME.plusSeconds(1)));
            pendingBar.update(tick(101.0, 200, lastTime));

            assertThat(pendingBar.getCloseTime()).isEqualTo(lastTime);
        }
    }

    private static Tick tick(double price, long volume, LocalDateTime timestamp) {
        return Tick.builder()
                .instrumentToken(256265L)
                .lastPrice(BigDecimal.valueOf(price))
                .volume(volume)
                .timestamp(timestamp)
                .build();
    }
}
