package com.algotrader.unit.indicator;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.indicator.IndicatorDefinition;
import com.algotrader.indicator.IndicatorFactory;
import com.algotrader.indicator.IndicatorType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;

/**
 * Tests for IndicatorFactory: verifies correct ta4j indicator creation
 * for each IndicatorType and proper key formatting.
 */
class IndicatorFactoryTest {

    private BarSeries series;

    @BeforeEach
    void setUp() {
        series = new BaseBarSeriesBuilder().withName("TEST").build();
        // Seed enough bars for most indicators to produce values
        Instant baseTime = Instant.parse("2025-02-10T03:45:00Z"); // 09:15 IST
        for (int i = 0; i < 30; i++) {
            double price = 22500 + (i * 10) - (i % 3 == 0 ? 5 : 0);
            series.addBar(series.barBuilder()
                    .timePeriod(Duration.ofMinutes(1))
                    .endTime(baseTime.plusSeconds(60L * (i + 1)))
                    .openPrice(price)
                    .highPrice(price + 15)
                    .lowPrice(price - 10)
                    .closePrice(price + 5)
                    .volume(1000 + i * 100)
                    .build());
        }
    }

    @Nested
    @DisplayName("Single-output indicators")
    class SingleOutputIndicators {

        @Test
        @DisplayName("RSI creates one indicator with correct key")
        void rsiCreatesOneIndicator() {
            IndicatorDefinition def = definition(IndicatorType.RSI, Map.of("period", 14));

            Map<String, Indicator<Num>> indicators = IndicatorFactory.createIndicators(series, List.of(def));

            assertThat(indicators).containsKey("RSI:14");
            assertThat(indicators).hasSize(1);
            // RSI should produce a value between 0-100
            Num value = indicators.get("RSI:14").getValue(series.getEndIndex());
            assertThat(value.doubleValue()).isBetween(0.0, 100.0);
        }

        @Test
        @DisplayName("EMA creates one indicator with correct key")
        void emaCreatesOneIndicator() {
            IndicatorDefinition def = definition(IndicatorType.EMA, Map.of("period", 21));

            Map<String, Indicator<Num>> indicators = IndicatorFactory.createIndicators(series, List.of(def));

            assertThat(indicators).containsKey("EMA:21");
            assertThat(indicators).hasSize(1);
        }

        @Test
        @DisplayName("SMA creates one indicator with correct key")
        void smaCreatesOneIndicator() {
            IndicatorDefinition def = definition(IndicatorType.SMA, Map.of("period", 20));

            Map<String, Indicator<Num>> indicators = IndicatorFactory.createIndicators(series, List.of(def));

            assertThat(indicators).containsKey("SMA:20");
            assertThat(indicators).hasSize(1);
        }

        @Test
        @DisplayName("ATR creates one indicator with correct key")
        void atrCreatesOneIndicator() {
            IndicatorDefinition def = definition(IndicatorType.ATR, Map.of("period", 14));

            Map<String, Indicator<Num>> indicators = IndicatorFactory.createIndicators(series, List.of(def));

            assertThat(indicators).containsKey("ATR:14");
            assertThat(indicators).hasSize(1);
        }

        @Test
        @DisplayName("VWAP creates one indicator with key period 0")
        void vwapCreatesOneIndicator() {
            IndicatorDefinition def = definition(IndicatorType.VWAP, Map.of());

            Map<String, Indicator<Num>> indicators = IndicatorFactory.createIndicators(series, List.of(def));

            assertThat(indicators).containsKey("VWAP:0");
            assertThat(indicators).hasSize(1);
        }

        @Test
        @DisplayName("LTP creates a close price indicator with key period 0")
        void ltpCreatesClosePriceIndicator() {
            IndicatorDefinition def = definition(IndicatorType.LTP, Map.of());

            Map<String, Indicator<Num>> indicators = IndicatorFactory.createIndicators(series, List.of(def));

            assertThat(indicators).containsKey("LTP:0");
            assertThat(indicators).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Multi-output indicators")
    class MultiOutputIndicators {

        @Test
        @DisplayName("Bollinger creates upper, middle, lower entries")
        void bollingerCreatesThreeEntries() {
            IndicatorDefinition def = definition(IndicatorType.BOLLINGER, Map.of("period", 20, "multiplier", 2.0));

            Map<String, Indicator<Num>> indicators = IndicatorFactory.createIndicators(series, List.of(def));

            assertThat(indicators).containsKeys("BOLLINGER:20:upper", "BOLLINGER:20:middle", "BOLLINGER:20:lower");
            assertThat(indicators).hasSize(3);

            // Upper should be above middle, lower should be below
            double upper = indicators
                    .get("BOLLINGER:20:upper")
                    .getValue(series.getEndIndex())
                    .doubleValue();
            double middle = indicators
                    .get("BOLLINGER:20:middle")
                    .getValue(series.getEndIndex())
                    .doubleValue();
            double lower = indicators
                    .get("BOLLINGER:20:lower")
                    .getValue(series.getEndIndex())
                    .doubleValue();
            assertThat(upper).isGreaterThan(middle);
            assertThat(lower).isLessThan(middle);
        }

        @Test
        @DisplayName("MACD creates value and signal entries")
        void macdCreatesTwoEntries() {
            IndicatorDefinition def =
                    definition(IndicatorType.MACD, Map.of("shortPeriod", 12, "longPeriod", 26, "signalPeriod", 9));

            Map<String, Indicator<Num>> indicators = IndicatorFactory.createIndicators(series, List.of(def));

            assertThat(indicators).containsKeys("MACD:12:value", "MACD:12:signal");
            assertThat(indicators).hasSize(2);
        }

        @Test
        @DisplayName("SuperTrend creates value, upper, lower entries")
        void supertrendCreatesThreeEntries() {
            IndicatorDefinition def = definition(IndicatorType.SUPERTREND, Map.of("period", 10, "multiplier", 3.0));

            Map<String, Indicator<Num>> indicators = IndicatorFactory.createIndicators(series, List.of(def));

            assertThat(indicators).containsKeys("SUPERTREND:10:value", "SUPERTREND:10:upper", "SUPERTREND:10:lower");
            assertThat(indicators).hasSize(3);
        }

        @Test
        @DisplayName("Stochastic creates k and d entries")
        void stochasticCreatesTwoEntries() {
            IndicatorDefinition def = definition(IndicatorType.STOCHASTIC, Map.of("period", 14));

            Map<String, Indicator<Num>> indicators = IndicatorFactory.createIndicators(series, List.of(def));

            assertThat(indicators).containsKeys("STOCHASTIC:14:k", "STOCHASTIC:14:d");
            assertThat(indicators).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Multiple definitions")
    class MultipleDefinitions {

        @Test
        @DisplayName("processes multiple indicator definitions")
        void processesMultipleDefinitions() {
            List<IndicatorDefinition> defs = List.of(
                    definition(IndicatorType.RSI, Map.of("period", 14)),
                    definition(IndicatorType.EMA, Map.of("period", 9)),
                    definition(IndicatorType.ATR, Map.of("period", 14)),
                    definition(IndicatorType.LTP, Map.of()));

            Map<String, Indicator<Num>> indicators = IndicatorFactory.createIndicators(series, defs);

            assertThat(indicators).hasSize(4);
            assertThat(indicators).containsKeys("RSI:14", "EMA:9", "ATR:14", "LTP:0");
        }
    }

    @Nested
    @DisplayName("Default parameters")
    class DefaultParameters {

        @Test
        @DisplayName("RSI defaults to period 14")
        void rsiDefaultsPeriod14() {
            IndicatorDefinition def = definition(IndicatorType.RSI, Map.of());

            Map<String, Indicator<Num>> indicators = IndicatorFactory.createIndicators(series, List.of(def));

            assertThat(indicators).containsKey("RSI:14");
        }

        @Test
        @DisplayName("EMA defaults to period 21")
        void emaDefaultsPeriod21() {
            IndicatorDefinition def = definition(IndicatorType.EMA, Map.of());

            Map<String, Indicator<Num>> indicators = IndicatorFactory.createIndicators(series, List.of(def));

            assertThat(indicators).containsKey("EMA:21");
        }

        @Test
        @DisplayName("SMA defaults to period 20")
        void smaDefaultsPeriod20() {
            IndicatorDefinition def = definition(IndicatorType.SMA, Map.of());

            Map<String, Indicator<Num>> indicators = IndicatorFactory.createIndicators(series, List.of(def));

            assertThat(indicators).containsKey("SMA:20");
        }
    }

    @Nested
    @DisplayName("buildKey")
    class BuildKey {

        @Test
        @DisplayName("single-output key format: TYPE:period")
        void singleOutputKeyFormat() {
            String key = IndicatorFactory.buildKey(IndicatorType.RSI, 14, null);
            assertThat(key).isEqualTo("RSI:14");
        }

        @Test
        @DisplayName("multi-output key format: TYPE:period:field")
        void multiOutputKeyFormat() {
            String key = IndicatorFactory.buildKey(IndicatorType.BOLLINGER, 20, "upper");
            assertThat(key).isEqualTo("BOLLINGER:20:upper");
        }
    }

    private static IndicatorDefinition definition(IndicatorType type, Map<String, Object> params) {
        IndicatorDefinition def = new IndicatorDefinition();
        def.setType(type);
        def.setParams(new java.util.HashMap<>(params));
        return def;
    }
}
