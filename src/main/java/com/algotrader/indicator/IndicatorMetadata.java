package com.algotrader.indicator;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/**
 * Describes an indicator's metadata for UI rendering and validation.
 *
 * <p>Contains the valid range (min/max), output fields, default parameters,
 * and parameter constraints. Used by the frontend's Condition Builder to
 * validate thresholds and display appropriate input controls.
 */
@Data
@Builder
public class IndicatorMetadata {

    private IndicatorType type;
    private String displayName;

    /** Minimum possible output value (e.g., 0 for RSI, null for unbounded). */
    private Double minValue;

    /** Maximum possible output value (e.g., 100 for RSI, null for unbounded). */
    private Double maxValue;

    /** Output field names for multi-output indicators (e.g., ["upper", "middle", "lower"]). */
    private List<String> outputFields;

    /** Default parameters for this indicator type. */
    private Map<String, Object> defaultParams;

    /**
     * Returns metadata for all supported indicator types.
     */
    public static List<IndicatorMetadata> allMetadata() {
        return List.of(
                IndicatorMetadata.builder()
                        .type(IndicatorType.RSI)
                        .displayName("Relative Strength Index")
                        .minValue(0.0)
                        .maxValue(100.0)
                        .outputFields(List.of("value"))
                        .defaultParams(Map.of("period", 14))
                        .build(),
                IndicatorMetadata.builder()
                        .type(IndicatorType.EMA)
                        .displayName("Exponential Moving Average")
                        .minValue(null)
                        .maxValue(null)
                        .outputFields(List.of("value"))
                        .defaultParams(Map.of("period", 21))
                        .build(),
                IndicatorMetadata.builder()
                        .type(IndicatorType.SMA)
                        .displayName("Simple Moving Average")
                        .minValue(null)
                        .maxValue(null)
                        .outputFields(List.of("value"))
                        .defaultParams(Map.of("period", 20))
                        .build(),
                IndicatorMetadata.builder()
                        .type(IndicatorType.MACD)
                        .displayName("MACD")
                        .minValue(null)
                        .maxValue(null)
                        .outputFields(List.of("value", "signal"))
                        .defaultParams(Map.of("shortPeriod", 12, "longPeriod", 26, "signalPeriod", 9))
                        .build(),
                IndicatorMetadata.builder()
                        .type(IndicatorType.BOLLINGER)
                        .displayName("Bollinger Bands")
                        .minValue(null)
                        .maxValue(null)
                        .outputFields(List.of("upper", "middle", "lower"))
                        .defaultParams(Map.of("period", 20, "multiplier", 2.0))
                        .build(),
                IndicatorMetadata.builder()
                        .type(IndicatorType.SUPERTREND)
                        .displayName("SuperTrend")
                        .minValue(null)
                        .maxValue(null)
                        .outputFields(List.of("value", "upper", "lower"))
                        .defaultParams(Map.of("period", 10, "multiplier", 3.0))
                        .build(),
                IndicatorMetadata.builder()
                        .type(IndicatorType.VWAP)
                        .displayName("Volume Weighted Average Price")
                        .minValue(null)
                        .maxValue(null)
                        .outputFields(List.of("value"))
                        .defaultParams(Map.of())
                        .build(),
                IndicatorMetadata.builder()
                        .type(IndicatorType.ATR)
                        .displayName("Average True Range")
                        .minValue(0.0)
                        .maxValue(null)
                        .outputFields(List.of("value"))
                        .defaultParams(Map.of("period", 14))
                        .build(),
                IndicatorMetadata.builder()
                        .type(IndicatorType.STOCHASTIC)
                        .displayName("Stochastic Oscillator")
                        .minValue(0.0)
                        .maxValue(100.0)
                        .outputFields(List.of("k", "d"))
                        .defaultParams(Map.of("period", 14))
                        .build(),
                IndicatorMetadata.builder()
                        .type(IndicatorType.LTP)
                        .displayName("Last Traded Price")
                        .minValue(null)
                        .maxValue(null)
                        .outputFields(List.of("value"))
                        .defaultParams(Map.of())
                        .build());
    }
}
