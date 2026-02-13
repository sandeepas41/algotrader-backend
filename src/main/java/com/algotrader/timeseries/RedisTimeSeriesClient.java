package com.algotrader.timeseries;

import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.output.IntegerOutput;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper over Spring Data Redis for executing Redis TimeSeries commands.
 *
 * <p>Redis TimeSeries is a Redis module that stores timestamped numeric data and
 * supports server-side OHLC aggregation via {@code TS.RANGE ... AGGREGATION}.
 * Since Spring Data Redis does not have native TS command support, this client
 * dispatches commands via Lettuce's native API with proper output types.
 *
 * <p>Spring Data's {@code connection.execute()} defaults to {@code ByteArrayOutput}
 * which cannot handle integer responses (TS.ADD returns a long timestamp). We use
 * Lettuce's {@code dispatch()} directly with the correct output type for each command:
 * {@code IntegerOutput} for TS.ADD, {@code StatusOutput} for TS.CREATE, and
 * {@code NestedMultiOutput} for TS.RANGE.
 *
 * <p>Key naming convention follows the project's {@code algo:} prefix:
 * <ul>
 *   <li>{@code algo:ts:ltp:{instrumentToken}} — raw LTP values</li>
 *   <li>{@code algo:ts:vol:{instrumentToken}} — delta volume values</li>
 * </ul>
 *
 * <p>TS.CREATE is called lazily on first write per key. If the key already exists,
 * the "ERR TSDB: key already exists" error is silently ignored.
 */
@Component
public class RedisTimeSeriesClient {

    private static final Logger log = LoggerFactory.getLogger(RedisTimeSeriesClient.class);

    /** Prefix for LTP time series keys. */
    public static final String KEY_PREFIX_LTP = "algo:ts:ltp:";

    /** Prefix for volume time series keys. */
    public static final String KEY_PREFIX_VOL = "algo:ts:vol:";

    private static final ByteArrayCodec CODEC = ByteArrayCodec.INSTANCE;

    /**
     * Custom protocol keywords for Redis TimeSeries module commands.
     * Lettuce requires a ProtocolKeyword to dispatch non-standard commands.
     */
    private enum TsCommand implements ProtocolKeyword {
        TS_ADD,
        TS_CREATE,
        TS_RANGE;

        private final byte[] bytes;

        TsCommand() {
            // TS_ADD → "TS.ADD", TS_CREATE → "TS.CREATE", TS_RANGE → "TS.RANGE"
            this.bytes = name().replace('_', '.').getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }
    }

    private final StringRedisTemplate stringRedisTemplate;

    public RedisTimeSeriesClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Create a TimeSeries key with retention and duplicate policy if it does not exist.
     * Silently ignores "key already exists" errors.
     *
     * @param key             the full Redis key (e.g., "algo:ts:ltp:256265")
     * @param retentionMs     retention period in milliseconds
     * @param duplicatePolicy "LAST" for LTP (overwrite same-ms), "SUM" for volume (accumulate)
     */
    public void createIfNotExists(String key, long retentionMs, String duplicatePolicy) {
        try {
            stringRedisTemplate.execute(
                    connection -> {
                        dispatch(
                                connection,
                                TsCommand.TS_CREATE,
                                new StatusOutput<>(CODEC),
                                new CommandArgs<>(CODEC)
                                        .add(key.getBytes())
                                        .add("RETENTION".getBytes())
                                        .add(String.valueOf(retentionMs).getBytes())
                                        .add("DUPLICATE_POLICY".getBytes())
                                        .add(duplicatePolicy.getBytes()));
                        return null;
                    },
                    true);
        } catch (Exception e) {
            // Silently ignore "key already exists" — TS.CREATE is idempotent in our usage
            if (e.getMessage() == null || !e.getMessage().contains("key already exists")) {
                log.warn("TS.CREATE failed for key {}: {}", key, e.getMessage());
            }
        }
    }

    /**
     * Append a sample to a TimeSeries key.
     *
     * @param key       the full Redis key
     * @param epochMs   timestamp in epoch milliseconds
     * @param value     the numeric value (LTP or delta volume)
     */
    public void add(String key, long epochMs, double value) {
        try {
            stringRedisTemplate.execute(
                    connection -> {
                        dispatch(
                                connection,
                                TsCommand.TS_ADD,
                                new IntegerOutput<>(CODEC),
                                new CommandArgs<>(CODEC)
                                        .add(key.getBytes())
                                        .add(String.valueOf(epochMs).getBytes())
                                        .add(String.valueOf(value).getBytes()));
                        return null;
                    },
                    true);
        } catch (Exception e) {
            log.error("TS.ADD failed for key {} at {}: {}", key, epochMs, e.getMessage());
        }
    }

    /**
     * Query a range with server-side aggregation.
     *
     * <p>Returns a list of [timestamp, value] pairs where each pair represents
     * one aggregation bucket. The aggregation type determines the computation:
     * "first" for open, "max" for high, "min" for low, "last" for close, "sum" for volume.
     *
     * @param key             the full Redis key
     * @param fromEpochMs     range start (inclusive)
     * @param toEpochMs       range end (inclusive)
     * @param aggregationType one of: "first", "max", "min", "last", "sum"
     * @param bucketDurationMs aggregation bucket size in milliseconds
     * @return list of TimeSeries samples (timestamp + value pairs)
     */
    public List<TsSample> range(
            String key, long fromEpochMs, long toEpochMs, String aggregationType, long bucketDurationMs) {

        List<TsSample> results = new ArrayList<>();

        try {
            List<Object> rawResults = stringRedisTemplate.execute(
                    connection -> {
                        return dispatch(
                                connection,
                                TsCommand.TS_RANGE,
                                new NestedMultiOutput<>(CODEC),
                                new CommandArgs<>(CODEC)
                                        .add(key.getBytes())
                                        .add(String.valueOf(fromEpochMs).getBytes())
                                        .add(String.valueOf(toEpochMs).getBytes())
                                        .add("AGGREGATION".getBytes())
                                        .add(aggregationType.getBytes())
                                        .add(String.valueOf(bucketDurationMs).getBytes()));
                    },
                    true);

            if (rawResults != null) {
                for (Object entry : rawResults) {
                    if (entry instanceof List<?> pair && pair.size() == 2) {
                        long timestamp = parseLong(pair.get(0));
                        double value = parseDouble(pair.get(1));
                        results.add(new TsSample(timestamp, value));
                    }
                }
            }
        } catch (Exception e) {
            // Key may not exist yet if no ticks have arrived for this instrument
            if (e.getMessage() == null || !e.getMessage().contains("key does not exist")) {
                log.error("TS.RANGE failed for key {}: {}", key, e.getMessage());
            }
        }

        return results;
    }

    /**
     * Dispatches a Redis TimeSeries command via Lettuce's native API with the correct
     * output type. Spring Data's connection.execute() defaults to ByteArrayOutput which
     * cannot handle integer responses from TS.ADD.
     *
     * <p>getNativeConnection() returns RedisAsyncCommands, so we get the StatefulConnection
     * from it and use the synchronous API to dispatch with proper output types.
     */
    @SuppressWarnings("unchecked")
    private <T> T dispatch(
            org.springframework.data.redis.connection.RedisConnection connection,
            ProtocolKeyword command,
            CommandOutput<byte[], byte[], T> output,
            CommandArgs<byte[], byte[]> args) {
        var asyncCommands = (RedisAsyncCommands<byte[], byte[]>) connection.getNativeConnection();
        return asyncCommands.getStatefulConnection().sync().dispatch(command, output, args);
    }

    private long parseLong(Object obj) {
        if (obj instanceof Long l) return l;
        if (obj instanceof byte[] bytes) return Long.parseLong(new String(bytes));
        return Long.parseLong(obj.toString());
    }

    private double parseDouble(Object obj) {
        if (obj instanceof Double d) return d;
        if (obj instanceof byte[] bytes) return Double.parseDouble(new String(bytes));
        return Double.parseDouble(obj.toString());
    }

    /**
     * A single sample from a Redis TimeSeries query result.
     *
     * @param timestamp epoch millisecond of the bucket start
     * @param value     the aggregated value for this bucket
     */
    public record TsSample(long timestamp, double value) {}
}
