package com.algotrader.indicator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Per-instrument configuration for indicator tracking.
 *
 * <p>Defines which instrument to track, the bar duration for OHLCV aggregation,
 * the maximum number of bars retained in memory, and which indicators to compute.
 * Different instruments can have different bar durations (e.g., 1min for NIFTY
 * scalping vs 5min for INDIA VIX).
 */
@Data
public class InstrumentIndicatorConfig {

    private Long instrumentToken;
    private String tradingSymbol;
    private Duration barDuration = Duration.ofMinutes(1);
    private int maxBars = 500;
    private List<IndicatorDefinition> indicators = new ArrayList<>();
}
