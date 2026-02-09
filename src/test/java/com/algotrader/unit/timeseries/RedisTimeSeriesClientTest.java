package com.algotrader.unit.timeseries;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.algotrader.timeseries.RedisTimeSeriesClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Unit tests for RedisTimeSeriesClient.
 * Verifies TS command execution via mocked StringRedisTemplate.
 */
@ExtendWith(MockitoExtension.class)
class RedisTimeSeriesClientTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private RedisTimeSeriesClient redisTimeSeriesClient;

    @BeforeEach
    void setUp() {
        redisTimeSeriesClient = new RedisTimeSeriesClient(stringRedisTemplate);
    }

    @Nested
    @DisplayName("createIfNotExists")
    class CreateIfNotExists {

        @Test
        @DisplayName("executes TS.CREATE command via StringRedisTemplate")
        void executesCreateCommand() {
            // The execute method is called on the template — we verify it doesn't throw
            when(stringRedisTemplate.execute(any(), eq(true))).thenReturn(null);

            redisTimeSeriesClient.createIfNotExists("algo:ts:ltp:256265", 604800000L, "LAST");

            verify(stringRedisTemplate).execute(any(), eq(true));
        }

        @Test
        @DisplayName("silently ignores 'key already exists' error")
        void ignoresKeyAlreadyExistsError() {
            when(stringRedisTemplate.execute(any(), eq(true)))
                    .thenThrow(new RuntimeException("ERR TSDB: key already exists"));

            // Should not throw
            assertDoesNotThrow(() -> redisTimeSeriesClient.createIfNotExists("algo:ts:ltp:256265", 604800000L, "LAST"));
        }

        @Test
        @DisplayName("logs warning for other errors")
        void logsWarningForOtherErrors() {
            when(stringRedisTemplate.execute(any(), eq(true))).thenThrow(new RuntimeException("Connection refused"));

            // Should not throw (error is logged, not propagated)
            assertDoesNotThrow(() -> redisTimeSeriesClient.createIfNotExists("algo:ts:ltp:256265", 604800000L, "LAST"));
        }
    }

    @Nested
    @DisplayName("add")
    class Add {

        @Test
        @DisplayName("executes TS.ADD command")
        void executesAddCommand() {
            when(stringRedisTemplate.execute(any(), eq(true))).thenReturn(null);

            redisTimeSeriesClient.add("algo:ts:ltp:256265", 1700000000000L, 22500.5);

            verify(stringRedisTemplate).execute(any(), eq(true));
        }

        @Test
        @DisplayName("handles errors gracefully without propagating")
        void handlesErrorsGracefully() {
            when(stringRedisTemplate.execute(any(), eq(true))).thenThrow(new RuntimeException("Connection error"));

            assertDoesNotThrow(() -> redisTimeSeriesClient.add("algo:ts:ltp:256265", 1700000000000L, 22500.5));
        }
    }

    @Nested
    @DisplayName("range")
    class Range {

        @Test
        @DisplayName("returns empty list when key does not exist")
        void returnsEmptyForNonExistentKey() {
            when(stringRedisTemplate.execute(any(), eq(true)))
                    .thenThrow(new RuntimeException("ERR TSDB: key does not exist"));

            List<RedisTimeSeriesClient.TsSample> result =
                    redisTimeSeriesClient.range("algo:ts:ltp:999999", 0, 1000, "first", 60000);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns empty list when result is null")
        void returnsEmptyForNullResult() {
            when(stringRedisTemplate.execute(any(), eq(true))).thenReturn(null);

            List<RedisTimeSeriesClient.TsSample> result =
                    redisTimeSeriesClient.range("algo:ts:ltp:256265", 0, 1000, "first", 60000);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("TsSample")
    class TsSampleTest {

        @Test
        @DisplayName("record holds timestamp and value")
        void holdsTimestampAndValue() {
            RedisTimeSeriesClient.TsSample sample = new RedisTimeSeriesClient.TsSample(1700000000000L, 22500.5);

            assertEquals(1700000000000L, sample.timestamp());
            assertEquals(22500.5, sample.value());
        }
    }

    @Nested
    @DisplayName("Key prefixes")
    class KeyPrefixes {

        @Test
        @DisplayName("LTP key prefix follows algo: convention")
        void ltpKeyPrefix() {
            assertEquals("algo:ts:ltp:", RedisTimeSeriesClient.KEY_PREFIX_LTP);
        }

        @Test
        @DisplayName("volume key prefix follows algo: convention")
        void volumeKeyPrefix() {
            assertEquals("algo:ts:vol:", RedisTimeSeriesClient.KEY_PREFIX_VOL);
        }
    }
}
