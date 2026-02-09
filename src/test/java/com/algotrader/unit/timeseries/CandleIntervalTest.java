package com.algotrader.unit.timeseries;

import static org.junit.jupiter.api.Assertions.*;

import com.algotrader.timeseries.CandleInterval;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for CandleInterval enum.
 * Verifies duration values, suffix strings, and fromSuffix() lookup.
 */
class CandleIntervalTest {

    @Nested
    @DisplayName("Duration values")
    class DurationValues {

        @Test
        @DisplayName("ONE_MINUTE is 60_000 ms")
        void oneMinute() {
            assertEquals(60_000L, CandleInterval.ONE_MINUTE.getDurationMs());
        }

        @Test
        @DisplayName("FIVE_MINUTES is 300_000 ms")
        void fiveMinutes() {
            assertEquals(300_000L, CandleInterval.FIVE_MINUTES.getDurationMs());
        }

        @Test
        @DisplayName("FIFTEEN_MINUTES is 900_000 ms")
        void fifteenMinutes() {
            assertEquals(900_000L, CandleInterval.FIFTEEN_MINUTES.getDurationMs());
        }

        @Test
        @DisplayName("ONE_HOUR is 3_600_000 ms")
        void oneHour() {
            assertEquals(3_600_000L, CandleInterval.ONE_HOUR.getDurationMs());
        }
    }

    @Nested
    @DisplayName("Suffix strings")
    class SuffixStrings {

        @Test
        @DisplayName("suffixes match expected values")
        void suffixesMatch() {
            assertEquals("1m", CandleInterval.ONE_MINUTE.getSuffix());
            assertEquals("5m", CandleInterval.FIVE_MINUTES.getSuffix());
            assertEquals("15m", CandleInterval.FIFTEEN_MINUTES.getSuffix());
            assertEquals("1h", CandleInterval.ONE_HOUR.getSuffix());
        }
    }

    @Nested
    @DisplayName("fromSuffix")
    class FromSuffix {

        @Test
        @DisplayName("resolves valid suffixes to correct intervals")
        void resolvesValidSuffixes() {
            assertEquals(CandleInterval.ONE_MINUTE, CandleInterval.fromSuffix("1m"));
            assertEquals(CandleInterval.FIVE_MINUTES, CandleInterval.fromSuffix("5m"));
            assertEquals(CandleInterval.FIFTEEN_MINUTES, CandleInterval.fromSuffix("15m"));
            assertEquals(CandleInterval.ONE_HOUR, CandleInterval.fromSuffix("1h"));
        }

        @Test
        @DisplayName("throws IllegalArgumentException for unknown suffix")
        void throwsForUnknownSuffix() {
            assertThrows(IllegalArgumentException.class, () -> CandleInterval.fromSuffix("2m"));
        }
    }
}
