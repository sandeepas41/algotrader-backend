package com.algotrader.timeseries;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the OHLC candle pipeline.
 *
 * <p>Binds to the {@code algotrader.ohlc.*} prefix in application.properties.
 * Controls whether tick ingestion into Redis TimeSeries is active, how long
 * data is retained, and which candle intervals are available for queries.
 *
 * <p>When {@code enabled} is false, the {@link OhlcTickListener} skips ingestion
 * and the {@link OhlcService} returns empty results. This allows disabling the
 * feature without removing the Redis TimeSeries module.
 */
@Configuration
@ConfigurationProperties(prefix = "algotrader.ohlc")
@Getter
@Setter
public class OhlcConfig {

    /** Whether the OHLC pipeline is active. When false, ticks are not written to Redis TS. */
    private boolean enabled = true;

    /** Number of days to retain raw tick data in Redis TimeSeries. Default: 7 days. */
    private int retentionDays = 7;

    /** Candle interval suffixes available for queries (e.g., "1m", "5m", "15m", "1h"). */
    private List<String> intervals = List.of("1m", "5m", "15m", "1h");

    /** Retention in milliseconds, derived from retentionDays. */
    public long getRetentionMs() {
        return retentionDays * 24L * 60 * 60 * 1000;
    }
}
