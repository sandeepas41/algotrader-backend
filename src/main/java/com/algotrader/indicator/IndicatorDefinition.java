package com.algotrader.indicator;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;

/**
 * Configuration for a single indicator instance within an instrument's indicator set.
 *
 * <p>Defines the indicator type and its parameters (e.g., period=14 for RSI,
 * multiplier=2.0 for Bollinger Bands). Parameters are stored as a flexible map
 * to accommodate varying indicator signatures without rigid schemas.
 */
@Data
public class IndicatorDefinition {

    private IndicatorType type;
    private Map<String, Object> params = new HashMap<>();

    public int getParamOrDefault(String key, int defaultValue) {
        Object value = params.get(key);
        return value != null ? Integer.parseInt(value.toString()) : defaultValue;
    }

    public double getDoubleParamOrDefault(String key, double defaultValue) {
        Object value = params.get(key);
        return value != null ? Double.parseDouble(value.toString()) : defaultValue;
    }
}
