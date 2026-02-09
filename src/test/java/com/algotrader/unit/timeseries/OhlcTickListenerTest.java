package com.algotrader.unit.timeseries;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.algotrader.domain.model.Tick;
import com.algotrader.event.TickEvent;
import com.algotrader.timeseries.OhlcConfig;
import com.algotrader.timeseries.OhlcTickListener;
import com.algotrader.timeseries.RedisTimeSeriesClient;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for OhlcTickListener.
 * Verifies tick ingestion, delta volume computation, lazy key initialization,
 * and the enabled/disabled toggle.
 */
@ExtendWith(MockitoExtension.class)
class OhlcTickListenerTest {

    @Mock
    private RedisTimeSeriesClient redisTimeSeriesClient;

    private OhlcConfig ohlcConfig;
    private OhlcTickListener ohlcTickListener;

    private static final long TOKEN = 256265L;

    @BeforeEach
    void setUp() {
        ohlcConfig = new OhlcConfig();
        ohlcConfig.setEnabled(true);
        ohlcConfig.setRetentionDays(7);
        ohlcTickListener = new OhlcTickListener(redisTimeSeriesClient, ohlcConfig);
    }

    private TickEvent createTickEvent(long token, double lastPrice, long volume) {
        Tick tick = Tick.builder()
                .instrumentToken(token)
                .lastPrice(BigDecimal.valueOf(lastPrice))
                .volume(volume)
                .build();
        return new TickEvent(this, tick);
    }

    @Nested
    @DisplayName("Tick ingestion")
    class TickIngestion {

        @Test
        @DisplayName("writes LTP to Redis TimeSeries on tick")
        void writesLtpOnTick() {
            TickEvent event = createTickEvent(TOKEN, 22500.50, 1000);

            ohlcTickListener.onTick(event);

            verify(redisTimeSeriesClient)
                    .add(eq(RedisTimeSeriesClient.KEY_PREFIX_LTP + TOKEN), anyLong(), eq(22500.50));
        }

        @Test
        @DisplayName("writes delta volume on first tick (delta = cumulative)")
        void writesDeltaVolumeOnFirstTick() {
            TickEvent event = createTickEvent(TOKEN, 22500.50, 5000);

            ohlcTickListener.onTick(event);

            // First tick: delta = 5000 - 0 = 5000
            verify(redisTimeSeriesClient).add(eq(RedisTimeSeriesClient.KEY_PREFIX_VOL + TOKEN), anyLong(), eq(5000.0));
        }

        @Test
        @DisplayName("computes correct delta volume across consecutive ticks")
        void computesDeltaVolume() {
            ohlcTickListener.onTick(createTickEvent(TOKEN, 22500.0, 1000));
            ohlcTickListener.onTick(createTickEvent(TOKEN, 22510.0, 1300));

            // Second tick delta: 1300 - 1000 = 300
            verify(redisTimeSeriesClient).add(eq(RedisTimeSeriesClient.KEY_PREFIX_VOL + TOKEN), anyLong(), eq(300.0));
        }

        @Test
        @DisplayName("skips volume write when delta is zero")
        void skipsZeroDeltaVolume() {
            ohlcTickListener.onTick(createTickEvent(TOKEN, 22500.0, 1000));

            // Reset mock to only track second call
            reset(redisTimeSeriesClient);

            ohlcTickListener.onTick(createTickEvent(TOKEN, 22510.0, 1000));

            // Volume didn't change, so only LTP is written
            verify(redisTimeSeriesClient).add(eq(RedisTimeSeriesClient.KEY_PREFIX_LTP + TOKEN), anyLong(), anyDouble());
            verify(redisTimeSeriesClient, never())
                    .add(eq(RedisTimeSeriesClient.KEY_PREFIX_VOL + TOKEN), anyLong(), anyDouble());
        }

        @Test
        @DisplayName("handles volume reset (negative delta) gracefully")
        void handlesVolumeReset() {
            ohlcTickListener.onTick(createTickEvent(TOKEN, 22500.0, 5000));

            // Reset mock to only track second call
            reset(redisTimeSeriesClient);

            // Volume decreased — can happen on day change or data reset
            ohlcTickListener.onTick(createTickEvent(TOKEN, 22510.0, 100));

            // Delta would be negative → clamped to 0, no volume write
            verify(redisTimeSeriesClient, never())
                    .add(eq(RedisTimeSeriesClient.KEY_PREFIX_VOL + TOKEN), anyLong(), anyDouble());
        }
    }

    @Nested
    @DisplayName("Lazy key initialization")
    class KeyInitialization {

        @Test
        @DisplayName("creates TS keys on first tick for a token")
        void createsKeysOnFirstTick() {
            ohlcTickListener.onTick(createTickEvent(TOKEN, 22500.0, 1000));

            verify(redisTimeSeriesClient)
                    .createIfNotExists(
                            eq(RedisTimeSeriesClient.KEY_PREFIX_LTP + TOKEN), eq(7L * 24 * 60 * 60 * 1000), eq("LAST"));
            verify(redisTimeSeriesClient)
                    .createIfNotExists(
                            eq(RedisTimeSeriesClient.KEY_PREFIX_VOL + TOKEN), eq(7L * 24 * 60 * 60 * 1000), eq("SUM"));
        }

        @Test
        @DisplayName("does not re-create keys on subsequent ticks for same token")
        void doesNotRecreateKeys() {
            ohlcTickListener.onTick(createTickEvent(TOKEN, 22500.0, 1000));
            ohlcTickListener.onTick(createTickEvent(TOKEN, 22510.0, 1100));

            // createIfNotExists should be called only once per key
            verify(redisTimeSeriesClient, times(1))
                    .createIfNotExists(eq(RedisTimeSeriesClient.KEY_PREFIX_LTP + TOKEN), anyLong(), anyString());
        }

        @Test
        @DisplayName("creates separate keys for different tokens")
        void createsSeparateKeysPerToken() {
            long otherToken = 260105L;

            ohlcTickListener.onTick(createTickEvent(TOKEN, 22500.0, 1000));
            ohlcTickListener.onTick(createTickEvent(otherToken, 48000.0, 500));

            verify(redisTimeSeriesClient)
                    .createIfNotExists(eq(RedisTimeSeriesClient.KEY_PREFIX_LTP + TOKEN), anyLong(), eq("LAST"));
            verify(redisTimeSeriesClient)
                    .createIfNotExists(eq(RedisTimeSeriesClient.KEY_PREFIX_LTP + otherToken), anyLong(), eq("LAST"));
        }
    }

    @Nested
    @DisplayName("Enabled/disabled toggle")
    class EnabledToggle {

        @Test
        @DisplayName("skips ingestion when disabled")
        void skipsWhenDisabled() {
            ohlcConfig.setEnabled(false);

            ohlcTickListener.onTick(createTickEvent(TOKEN, 22500.0, 1000));

            verifyNoInteractions(redisTimeSeriesClient);
        }
    }

    @Nested
    @DisplayName("Volume state reset")
    class VolumeReset {

        @Test
        @DisplayName("resetVolumeState clears tracking so next tick has full delta")
        void resetClearsVolumeTracking() {
            ohlcTickListener.onTick(createTickEvent(TOKEN, 22500.0, 5000));
            ohlcTickListener.resetVolumeState();

            // Reset mock to track only the post-reset tick
            reset(redisTimeSeriesClient);

            ohlcTickListener.onTick(createTickEvent(TOKEN, 22510.0, 200));

            // After reset, delta = 200 - 0 = 200 (not 200 - 5000)
            verify(redisTimeSeriesClient).add(eq(RedisTimeSeriesClient.KEY_PREFIX_VOL + TOKEN), anyLong(), eq(200.0));
        }
    }
}
