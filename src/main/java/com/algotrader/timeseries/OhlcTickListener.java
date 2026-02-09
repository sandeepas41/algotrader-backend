package com.algotrader.timeseries;

import com.algotrader.domain.model.Tick;
import com.algotrader.event.TickEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Ingests market data ticks into Redis TimeSeries for OHLC candle computation.
 *
 * <p>Listens to {@link TickEvent} at {@code @Order(7)} — after core processing (P&L,
 * strategy evaluation, risk) but before the WebSocket relay to frontend (Order 10).
 * Runs async to avoid blocking the tick processing pipeline.
 *
 * <p>For each tick, writes two Redis TimeSeries samples:
 * <ul>
 *   <li>LTP → {@code algo:ts:ltp:{token}} with DUPLICATE_POLICY LAST</li>
 *   <li>Delta volume → {@code algo:ts:vol:{token}} with DUPLICATE_POLICY SUM</li>
 * </ul>
 *
 * <p>Delta volume is computed as {@code currentVolume - lastKnownVolume} for each
 * instrument. Kite reports cumulative volume for the day, so we track the last seen
 * value to compute the incremental volume per tick interval. The first tick of the
 * day will have delta = currentVolume (since last known is 0).
 *
 * <p>TimeSeries keys are lazily created on the first tick for each instrument.
 * The set of initialized keys is tracked in memory to avoid repeated TS.CREATE calls.
 */
@Component
public class OhlcTickListener {

    private static final Logger log = LoggerFactory.getLogger(OhlcTickListener.class);

    private final RedisTimeSeriesClient redisTimeSeriesClient;
    private final OhlcConfig ohlcConfig;

    /** Tracks the last cumulative volume per instrument for delta computation. */
    private final ConcurrentHashMap<Long, Long> lastVolumeByToken = new ConcurrentHashMap<>();

    /** Set of instrument tokens whose TS keys have been created this session. */
    private final Set<Long> initializedTokens = ConcurrentHashMap.newKeySet();

    public OhlcTickListener(RedisTimeSeriesClient redisTimeSeriesClient, OhlcConfig ohlcConfig) {
        this.redisTimeSeriesClient = redisTimeSeriesClient;
        this.ohlcConfig = ohlcConfig;
    }

    @Async("eventExecutor")
    @EventListener
    @Order(7)
    public void onTick(TickEvent tickEvent) {
        if (!ohlcConfig.isEnabled()) {
            return;
        }

        Tick tick = tickEvent.getTick();
        long token = tick.getInstrumentToken();
        long epochMs = System.currentTimeMillis();

        // Lazy initialization — create TS keys on first tick per instrument
        if (initializedTokens.add(token)) {
            initializeKeys(token);
        }

        // Write LTP sample
        double ltp = tick.getLastPrice().doubleValue();
        redisTimeSeriesClient.add(RedisTimeSeriesClient.KEY_PREFIX_LTP + token, epochMs, ltp);

        // Compute and write delta volume
        long currentVolume = tick.getVolume();
        long previousVolume = lastVolumeByToken.getOrDefault(token, 0L);
        long deltaVolume = Math.max(0, currentVolume - previousVolume);
        lastVolumeByToken.put(token, currentVolume);

        if (deltaVolume > 0) {
            redisTimeSeriesClient.add(RedisTimeSeriesClient.KEY_PREFIX_VOL + token, epochMs, deltaVolume);
        }
    }

    private void initializeKeys(long token) {
        long retentionMs = ohlcConfig.getRetentionMs();

        redisTimeSeriesClient.createIfNotExists(RedisTimeSeriesClient.KEY_PREFIX_LTP + token, retentionMs, "LAST");
        redisTimeSeriesClient.createIfNotExists(RedisTimeSeriesClient.KEY_PREFIX_VOL + token, retentionMs, "SUM");

        log.debug("Initialized Redis TimeSeries keys for instrument token {}", token);
    }

    /**
     * Reset the volume tracking state. Called at the start of each trading day
     * to ensure delta volume calculation restarts cleanly.
     */
    public void resetVolumeState() {
        lastVolumeByToken.clear();
        log.info("Reset OHLC volume tracking state for new trading day");
    }
}
