package com.algotrader.indicator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.StochasticOscillatorDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.supertrend.SuperTrendIndicator;
import org.ta4j.core.indicators.supertrend.SuperTrendLowerBandIndicator;
import org.ta4j.core.indicators.supertrend.SuperTrendUpperBandIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;
import org.ta4j.core.num.Num;

/**
 * Creates ta4j indicator instances from {@link IndicatorDefinition} configurations.
 *
 * <p>Each indicator type maps to one or more ta4j Indicator objects. Multi-output
 * indicators (Bollinger, MACD, SuperTrend, Stochastic) produce multiple keyed entries
 * in the returned map. The key format is {@code TYPE:period} or {@code TYPE:period:field}.
 *
 * <p>This factory is stateless and can be called for any BarSeries. The returned
 * indicators hold a reference to the BarSeries and automatically recalculate when
 * new bars are added.
 */
public final class IndicatorFactory {

    private static final Logger log = LoggerFactory.getLogger(IndicatorFactory.class);

    private IndicatorFactory() {}

    /**
     * Creates all indicators for a given BarSeries from a list of definitions.
     *
     * @param series      the ta4j BarSeries to attach indicators to
     * @param definitions the indicator configuration list
     * @return map of indicator key -> ta4j Indicator instance
     */
    public static Map<String, Indicator<Num>> createIndicators(
            BarSeries series, List<IndicatorDefinition> definitions) {
        Map<String, Indicator<Num>> indicators = new LinkedHashMap<>();
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        for (IndicatorDefinition def : definitions) {
            switch (def.getType()) {
                case RSI -> {
                    int period = def.getParamOrDefault("period", 14);
                    indicators.put(buildKey(IndicatorType.RSI, period, null), new RSIIndicator(closePrice, period));
                }
                case EMA -> {
                    int period = def.getParamOrDefault("period", 21);
                    indicators.put(buildKey(IndicatorType.EMA, period, null), new EMAIndicator(closePrice, period));
                }
                case SMA -> {
                    int period = def.getParamOrDefault("period", 20);
                    indicators.put(buildKey(IndicatorType.SMA, period, null), new SMAIndicator(closePrice, period));
                }
                case BOLLINGER -> {
                    int period = def.getParamOrDefault("period", 20);
                    double multiplier = def.getDoubleParamOrDefault("multiplier", 2.0);
                    SMAIndicator sma = new SMAIndicator(closePrice, period);
                    StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, period);
                    Num k = series.numFactory().numOf(multiplier);

                    BollingerBandsMiddleIndicator middle = new BollingerBandsMiddleIndicator(sma);
                    BollingerBandsUpperIndicator upper = new BollingerBandsUpperIndicator(middle, stdDev, k);
                    BollingerBandsLowerIndicator lower = new BollingerBandsLowerIndicator(middle, stdDev, k);

                    indicators.put(buildKey(IndicatorType.BOLLINGER, period, "upper"), upper);
                    indicators.put(buildKey(IndicatorType.BOLLINGER, period, "middle"), middle);
                    indicators.put(buildKey(IndicatorType.BOLLINGER, period, "lower"), lower);
                }
                case SUPERTREND -> {
                    int period = def.getParamOrDefault("period", 10);
                    double multiplier = def.getDoubleParamOrDefault("multiplier", 3.0);
                    ATRIndicator atrForSt = new ATRIndicator(series, period);

                    indicators.put(
                            buildKey(IndicatorType.SUPERTREND, period, "value"),
                            new SuperTrendIndicator(series, period, multiplier));
                    indicators.put(
                            buildKey(IndicatorType.SUPERTREND, period, "upper"),
                            new SuperTrendUpperBandIndicator(series, atrForSt, multiplier));
                    indicators.put(
                            buildKey(IndicatorType.SUPERTREND, period, "lower"),
                            new SuperTrendLowerBandIndicator(series, atrForSt, multiplier));
                }
                case VWAP -> {
                    // VWAP uses the full bar count as the period
                    int period = Math.max(series.getBarCount(), 1);
                    indicators.put(buildKey(IndicatorType.VWAP, 0, null), new VWAPIndicator(series, period));
                }
                case ATR -> {
                    int period = def.getParamOrDefault("period", 14);
                    indicators.put(buildKey(IndicatorType.ATR, period, null), new ATRIndicator(series, period));
                }
                case MACD -> {
                    int shortPeriod = def.getParamOrDefault("shortPeriod", 12);
                    int longPeriod = def.getParamOrDefault("longPeriod", 26);
                    int signalPeriod = def.getParamOrDefault("signalPeriod", 9);
                    MACDIndicator macd = new MACDIndicator(closePrice, shortPeriod, longPeriod);
                    EMAIndicator signal = new EMAIndicator(macd, signalPeriod);

                    indicators.put(buildKey(IndicatorType.MACD, shortPeriod, "value"), macd);
                    indicators.put(buildKey(IndicatorType.MACD, shortPeriod, "signal"), signal);
                }
                case STOCHASTIC -> {
                    int period = def.getParamOrDefault("period", 14);
                    StochasticOscillatorKIndicator k = new StochasticOscillatorKIndicator(series, period);
                    StochasticOscillatorDIndicator d = new StochasticOscillatorDIndicator(k);

                    indicators.put(buildKey(IndicatorType.STOCHASTIC, period, "k"), k);
                    indicators.put(buildKey(IndicatorType.STOCHASTIC, period, "d"), d);
                }
                case LTP -> {
                    // LTP is a pseudo-indicator; ClosePriceIndicator serves as its proxy
                    indicators.put(buildKey(IndicatorType.LTP, 0, null), closePrice);
                }
            }
        }

        log.debug("Created {} indicator entries for series '{}'", indicators.size(), series.getName());
        return indicators;
    }

    /**
     * Builds a cache key for an indicator value.
     *
     * <p>Format: {@code TYPE:period} or {@code TYPE:period:field} for multi-output indicators.
     */
    public static String buildKey(IndicatorType type, int period, String field) {
        String key = type.name() + ":" + period;
        if (field != null) {
            key += ":" + field;
        }
        return key;
    }
}
