package com.algotrader.timeseries;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Business logic for querying OHLC candles from Redis TimeSeries.
 *
 * <p>Candles are computed on-demand by executing 5 parallel {@code TS.RANGE} queries
 * with different aggregation types (first/max/min/last/sum) and merging the results
 * by timestamp index. This avoids maintaining pre-computed aggregation keys per interval.
 *
 * <p>Trade-off: 5 Redis round-trips per query vs. 16N pre-computed keys (4 intervals
 * × 4 OHLC fields × N instruments). For a single-user trading platform, the on-demand
 * approach is simpler and Redis TimeSeries aggregation is sub-millisecond.
 */
@Service
public class OhlcService {

    private static final Logger log = LoggerFactory.getLogger(OhlcService.class);

    private final RedisTimeSeriesClient redisTimeSeriesClient;
    private final OhlcConfig ohlcConfig;

    public OhlcService(RedisTimeSeriesClient redisTimeSeriesClient, OhlcConfig ohlcConfig) {
        this.redisTimeSeriesClient = redisTimeSeriesClient;
        this.ohlcConfig = ohlcConfig;
    }

    /**
     * Retrieve OHLCV candles for an instrument over a time range.
     *
     * <p>Executes 5 {@code TS.RANGE} calls with server-side aggregation:
     * first (open), max (high), min (low), last (close), sum (volume).
     * Results are merged by aligning on bucket timestamps.
     *
     * @param instrumentToken the Kite instrument token
     * @param interval        the candle interval (e.g., ONE_MINUTE, FIVE_MINUTES)
     * @param fromEpochMs     range start in epoch milliseconds (inclusive)
     * @param toEpochMs       range end in epoch milliseconds (inclusive)
     * @return list of Candle objects, ordered by timestamp ascending; empty if disabled or no data
     */
    public List<Candle> getCandles(long instrumentToken, CandleInterval interval, long fromEpochMs, long toEpochMs) {
        if (!ohlcConfig.isEnabled()) {
            return Collections.emptyList();
        }

        String ltpKey = RedisTimeSeriesClient.KEY_PREFIX_LTP + instrumentToken;
        String volKey = RedisTimeSeriesClient.KEY_PREFIX_VOL + instrumentToken;
        long bucketMs = interval.getDurationMs();

        // Execute 5 TS.RANGE queries for OHLCV components
        List<RedisTimeSeriesClient.TsSample> opens =
                redisTimeSeriesClient.range(ltpKey, fromEpochMs, toEpochMs, "first", bucketMs);
        List<RedisTimeSeriesClient.TsSample> highs =
                redisTimeSeriesClient.range(ltpKey, fromEpochMs, toEpochMs, "max", bucketMs);
        List<RedisTimeSeriesClient.TsSample> lows =
                redisTimeSeriesClient.range(ltpKey, fromEpochMs, toEpochMs, "min", bucketMs);
        List<RedisTimeSeriesClient.TsSample> closes =
                redisTimeSeriesClient.range(ltpKey, fromEpochMs, toEpochMs, "last", bucketMs);
        List<RedisTimeSeriesClient.TsSample> volumes =
                redisTimeSeriesClient.range(volKey, fromEpochMs, toEpochMs, "sum", bucketMs);

        return mergeCandles(instrumentToken, interval, opens, highs, lows, closes, volumes);
    }

    /**
     * Merge 5 aggregation result arrays into a list of Candle objects.
     * Arrays are aligned by index — Redis TimeSeries guarantees consistent bucket timestamps
     * when querying the same time range with the same bucket size.
     */
    private List<Candle> mergeCandles(
            long instrumentToken,
            CandleInterval interval,
            List<RedisTimeSeriesClient.TsSample> opens,
            List<RedisTimeSeriesClient.TsSample> highs,
            List<RedisTimeSeriesClient.TsSample> lows,
            List<RedisTimeSeriesClient.TsSample> closes,
            List<RedisTimeSeriesClient.TsSample> volumes) {

        if (opens.isEmpty()) {
            return Collections.emptyList();
        }

        List<Candle> candles = new ArrayList<>(opens.size());

        for (int i = 0; i < opens.size(); i++) {
            long timestamp = opens.get(i).timestamp();

            Candle candle = Candle.builder()
                    .instrumentToken(instrumentToken)
                    .interval(interval)
                    .timestamp(timestamp)
                    .open(BigDecimal.valueOf(opens.get(i).value()))
                    .high(i < highs.size() ? BigDecimal.valueOf(highs.get(i).value()) : BigDecimal.ZERO)
                    .low(i < lows.size() ? BigDecimal.valueOf(lows.get(i).value()) : BigDecimal.ZERO)
                    .close(i < closes.size() ? BigDecimal.valueOf(closes.get(i).value()) : BigDecimal.ZERO)
                    .volume(i < volumes.size() ? (long) volumes.get(i).value() : 0L)
                    .build();

            candles.add(candle);
        }

        return candles;
    }
}
