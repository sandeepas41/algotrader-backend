package com.algotrader.unit.timeseries;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.algotrader.timeseries.Candle;
import com.algotrader.timeseries.CandleInterval;
import com.algotrader.timeseries.OhlcConfig;
import com.algotrader.timeseries.OhlcService;
import com.algotrader.timeseries.RedisTimeSeriesClient;
import com.algotrader.timeseries.RedisTimeSeriesClient.TsSample;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for OhlcService.
 * Verifies candle query logic: 5 TS.RANGE calls merged into Candle objects.
 */
@ExtendWith(MockitoExtension.class)
class OhlcServiceTest {

    @Mock
    private RedisTimeSeriesClient redisTimeSeriesClient;

    private OhlcConfig ohlcConfig;
    private OhlcService ohlcService;

    private static final long TOKEN = 256265L;
    private static final long FROM = 1700000000000L;
    private static final long TO = 1700000300000L;
    private static final CandleInterval INTERVAL = CandleInterval.ONE_MINUTE;

    @BeforeEach
    void setUp() {
        ohlcConfig = new OhlcConfig();
        ohlcConfig.setEnabled(true);
        ohlcService = new OhlcService(redisTimeSeriesClient, ohlcConfig);
    }

    @Nested
    @DisplayName("getCandles")
    class GetCandles {

        @Test
        @DisplayName("merges 5 TS.RANGE results into candles")
        void mergesFiveQueries() {
            String ltpKey = RedisTimeSeriesClient.KEY_PREFIX_LTP + TOKEN;
            String volKey = RedisTimeSeriesClient.KEY_PREFIX_VOL + TOKEN;
            long bucket = INTERVAL.getDurationMs();

            long t1 = 1700000000000L;
            long t2 = 1700000060000L;

            when(redisTimeSeriesClient.range(ltpKey, FROM, TO, "first", bucket))
                    .thenReturn(List.of(new TsSample(t1, 22500.0), new TsSample(t2, 22510.0)));
            when(redisTimeSeriesClient.range(ltpKey, FROM, TO, "max", bucket))
                    .thenReturn(List.of(new TsSample(t1, 22520.0), new TsSample(t2, 22530.0)));
            when(redisTimeSeriesClient.range(ltpKey, FROM, TO, "min", bucket))
                    .thenReturn(List.of(new TsSample(t1, 22490.0), new TsSample(t2, 22505.0)));
            when(redisTimeSeriesClient.range(ltpKey, FROM, TO, "last", bucket))
                    .thenReturn(List.of(new TsSample(t1, 22515.0), new TsSample(t2, 22525.0)));
            when(redisTimeSeriesClient.range(volKey, FROM, TO, "sum", bucket))
                    .thenReturn(List.of(new TsSample(t1, 15000.0), new TsSample(t2, 12000.0)));

            List<Candle> candles = ohlcService.getCandles(TOKEN, INTERVAL, FROM, TO);

            assertEquals(2, candles.size());

            Candle first = candles.get(0);
            assertEquals(TOKEN, first.getInstrumentToken());
            assertEquals(INTERVAL, first.getInterval());
            assertEquals(t1, first.getTimestamp());
            assertEquals(new BigDecimal("22500.0"), first.getOpen());
            assertEquals(new BigDecimal("22520.0"), first.getHigh());
            assertEquals(new BigDecimal("22490.0"), first.getLow());
            assertEquals(new BigDecimal("22515.0"), first.getClose());
            assertEquals(15000L, first.getVolume());

            Candle second = candles.get(1);
            assertEquals(t2, second.getTimestamp());
            assertEquals(new BigDecimal("22510.0"), second.getOpen());
            assertEquals(new BigDecimal("22530.0"), second.getHigh());
            assertEquals(new BigDecimal("22505.0"), second.getLow());
            assertEquals(new BigDecimal("22525.0"), second.getClose());
            assertEquals(12000L, second.getVolume());
        }

        @Test
        @DisplayName("returns empty list when no data available")
        void returnsEmptyWhenNoData() {
            when(redisTimeSeriesClient.range(anyString(), anyLong(), anyLong(), anyString(), anyLong()))
                    .thenReturn(Collections.emptyList());

            List<Candle> candles = ohlcService.getCandles(TOKEN, INTERVAL, FROM, TO);

            assertTrue(candles.isEmpty());
        }

        @Test
        @DisplayName("returns empty list when disabled")
        void returnsEmptyWhenDisabled() {
            ohlcConfig.setEnabled(false);

            List<Candle> candles = ohlcService.getCandles(TOKEN, INTERVAL, FROM, TO);

            assertTrue(candles.isEmpty());
            verifyNoInteractions(redisTimeSeriesClient);
        }

        @Test
        @DisplayName("handles missing volume data gracefully")
        void handlesMissingVolumeData() {
            String ltpKey = RedisTimeSeriesClient.KEY_PREFIX_LTP + TOKEN;
            String volKey = RedisTimeSeriesClient.KEY_PREFIX_VOL + TOKEN;
            long bucket = INTERVAL.getDurationMs();
            long t1 = 1700000000000L;

            when(redisTimeSeriesClient.range(ltpKey, FROM, TO, "first", bucket))
                    .thenReturn(List.of(new TsSample(t1, 22500.0)));
            when(redisTimeSeriesClient.range(ltpKey, FROM, TO, "max", bucket))
                    .thenReturn(List.of(new TsSample(t1, 22520.0)));
            when(redisTimeSeriesClient.range(ltpKey, FROM, TO, "min", bucket))
                    .thenReturn(List.of(new TsSample(t1, 22490.0)));
            when(redisTimeSeriesClient.range(ltpKey, FROM, TO, "last", bucket))
                    .thenReturn(List.of(new TsSample(t1, 22515.0)));
            // No volume data
            when(redisTimeSeriesClient.range(volKey, FROM, TO, "sum", bucket)).thenReturn(Collections.emptyList());

            List<Candle> candles = ohlcService.getCandles(TOKEN, INTERVAL, FROM, TO);

            assertEquals(1, candles.size());
            assertEquals(0L, candles.get(0).getVolume());
        }

        @Test
        @DisplayName("queries correct keys and parameters for FIVE_MINUTES interval")
        void queriesCorrectParametersForFiveMinutes() {
            String ltpKey = RedisTimeSeriesClient.KEY_PREFIX_LTP + TOKEN;
            String volKey = RedisTimeSeriesClient.KEY_PREFIX_VOL + TOKEN;
            long bucket = CandleInterval.FIVE_MINUTES.getDurationMs();

            when(redisTimeSeriesClient.range(anyString(), anyLong(), anyLong(), anyString(), anyLong()))
                    .thenReturn(Collections.emptyList());

            ohlcService.getCandles(TOKEN, CandleInterval.FIVE_MINUTES, FROM, TO);

            verify(redisTimeSeriesClient).range(ltpKey, FROM, TO, "first", bucket);
            verify(redisTimeSeriesClient).range(ltpKey, FROM, TO, "max", bucket);
            verify(redisTimeSeriesClient).range(ltpKey, FROM, TO, "min", bucket);
            verify(redisTimeSeriesClient).range(ltpKey, FROM, TO, "last", bucket);
            verify(redisTimeSeriesClient).range(volKey, FROM, TO, "sum", bucket);
        }
    }
}
