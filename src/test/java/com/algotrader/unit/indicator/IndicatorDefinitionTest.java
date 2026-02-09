package com.algotrader.unit.indicator;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.indicator.IndicatorDefinition;
import com.algotrader.indicator.IndicatorType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for IndicatorDefinition parameter extraction.
 */
class IndicatorDefinitionTest {

    @Test
    @DisplayName("getParamOrDefault returns param value when present")
    void getParamReturnsValueWhenPresent() {
        IndicatorDefinition def = new IndicatorDefinition();
        def.setType(IndicatorType.RSI);
        def.setParams(new HashMap<>(Map.of("period", 21)));

        assertThat(def.getParamOrDefault("period", 14)).isEqualTo(21);
    }

    @Test
    @DisplayName("getParamOrDefault returns default when param missing")
    void getParamReturnsDefaultWhenMissing() {
        IndicatorDefinition def = new IndicatorDefinition();
        def.setType(IndicatorType.RSI);

        assertThat(def.getParamOrDefault("period", 14)).isEqualTo(14);
    }

    @Test
    @DisplayName("getDoubleParamOrDefault returns param value when present")
    void getDoubleParamReturnsValueWhenPresent() {
        IndicatorDefinition def = new IndicatorDefinition();
        def.setType(IndicatorType.BOLLINGER);
        def.setParams(new HashMap<>(Map.of("multiplier", 2.5)));

        assertThat(def.getDoubleParamOrDefault("multiplier", 2.0)).isEqualTo(2.5);
    }

    @Test
    @DisplayName("getDoubleParamOrDefault returns default when param missing")
    void getDoubleParamReturnsDefaultWhenMissing() {
        IndicatorDefinition def = new IndicatorDefinition();
        def.setType(IndicatorType.BOLLINGER);

        assertThat(def.getDoubleParamOrDefault("multiplier", 2.0)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("getParamOrDefault parses string values")
    void getParamParsesStringValues() {
        IndicatorDefinition def = new IndicatorDefinition();
        def.setType(IndicatorType.RSI);
        def.setParams(new HashMap<>(Map.of("period", "21")));

        assertThat(def.getParamOrDefault("period", 14)).isEqualTo(21);
    }

    @Test
    @DisplayName("getDoubleParamOrDefault parses string values")
    void getDoubleParamParsesStringValues() {
        IndicatorDefinition def = new IndicatorDefinition();
        def.setType(IndicatorType.SUPERTREND);
        def.setParams(new HashMap<>(Map.of("multiplier", "3.5")));

        assertThat(def.getDoubleParamOrDefault("multiplier", 3.0)).isEqualTo(3.5);
    }
}
