package com.algotrader.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for tick recording.
 *
 * <p>Controls where tick data is stored, buffer flush intervals,
 * and post-market compression/cleanup settings.
 */
@Configuration
@ConfigurationProperties(prefix = "algotrader.tick-recorder")
@Getter
@Setter
public class TickRecorderConfig {

    /** Base directory for tick recording files. Date-based subdirectories are created under this. */
    private String recordingDirectory = "data/ticks";

    /** Number of ticks to buffer before flushing to disk. */
    private int bufferFlushSize = 5000;

    /** Periodic flush interval in milliseconds (default: 5 minutes). */
    private long flushIntervalMs = 300_000;

    /** Whether to auto-start recording on market open. */
    private boolean autoStartOnMarketOpen = true;

    /** Whether to compress files after market close. */
    private boolean compressAfterClose = true;

    /** Number of days to keep uncompressed local files before cleanup. */
    private int localRetentionDays = 30;
}
