package com.algotrader.timeseries;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * A single OHLCV candle aggregated from raw tick data stored in Redis TimeSeries.
 *
 * <p>Candles are computed on-demand by querying Redis TimeSeries with
 * {@code TS.RANGE ... AGGREGATION first/max/min/last/sum} — no pre-computed
 * aggregation keys are needed. Each candle represents one interval bucket
 * (e.g., 1 minute, 5 minutes) of market data for a single instrument.
 *
 * <p>All price fields use {@link BigDecimal} for precision in financial calculations.
 * The {@code timestamp} is the epoch millisecond of the bucket start.
 */
@Data
@Builder
public class Candle {

    /** Kite instrument token identifying the instrument. */
    private long instrumentToken;

    /** The aggregation interval this candle represents. */
    private CandleInterval interval;

    /** Epoch millisecond of the candle bucket start. */
    private long timestamp;

    /** First traded price in this interval. */
    private BigDecimal open;

    /** Highest traded price in this interval. */
    private BigDecimal high;

    /** Lowest traded price in this interval. */
    private BigDecimal low;

    /** Last traded price in this interval. */
    private BigDecimal close;

    /** Total traded volume in this interval (sum of delta volumes). */
    private long volume;
}
