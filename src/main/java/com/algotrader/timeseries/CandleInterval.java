package com.algotrader.timeseries;

/**
 * Supported OHLC candle intervals for Redis TimeSeries aggregation.
 *
 * <p>Each interval defines a duration in milliseconds and a short suffix used
 * in configuration and REST API query parameters (e.g., "1m", "5m", "15m", "1h").
 * The duration is passed directly to Redis TimeSeries {@code TS.RANGE AGGREGATION}
 * commands as the bucket size.
 */
public enum CandleInterval {
    ONE_MINUTE(60_000L, "1m"),
    FIVE_MINUTES(300_000L, "5m"),
    FIFTEEN_MINUTES(900_000L, "15m"),
    ONE_HOUR(3_600_000L, "1h");

    private final long durationMs;
    private final String suffix;

    CandleInterval(long durationMs, String suffix) {
        this.durationMs = durationMs;
        this.suffix = suffix;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getSuffix() {
        return suffix;
    }

    /**
     * Resolve a CandleInterval from its suffix string (e.g., "5m" → FIVE_MINUTES).
     *
     * @throws IllegalArgumentException if no interval matches the suffix
     */
    public static CandleInterval fromSuffix(String suffix) {
        for (CandleInterval interval : values()) {
            if (interval.suffix.equals(suffix)) {
                return interval;
            }
        }
        throw new IllegalArgumentException("Unknown candle interval suffix: " + suffix);
    }
}
