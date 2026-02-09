package com.algotrader.indicator;

import com.algotrader.domain.model.Tick;
import com.algotrader.event.IndicatorUpdateEvent;
import com.algotrader.event.TickEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.num.Num;

/**
 * Core service for technical indicator computation using ta4j.
 *
 * <p>Manages per-instrument BarSeries, accumulates ticks into OHLCV bars, and
 * recalculates registered indicators when bars complete. Indicator values are
 * cached in a ConcurrentHashMap for O(1) reads by the ConditionEngine and REST API.
 *
 * <p>Processing order: This service listens to TickEvents at {@code @Order(2)},
 * after the TickProcessor (1) but before PositionService (3) and StrategyEngine (4).
 *
 * <p>Lazy calculation: Only instruments with registered indicators are tracked.
 * Ticks for non-tracked instruments are ignored with no overhead.
 *
 * <p>Thread safety: Each BarSeriesManager has its own ReadWriteLock. The indicator
 * cache is a ConcurrentHashMap with happens-before guarantees on put/get.
 */
@Service
@EnableConfigurationProperties(IndicatorConfig.class)
public class IndicatorService {

    private static final Logger log = LoggerFactory.getLogger(IndicatorService.class);

    private final IndicatorConfig indicatorConfig;
    private final HistoricalDataSeeder historicalDataSeeder;
    private final ApplicationEventPublisher applicationEventPublisher;

    /** BarSeriesManager per instrument token. */
    private final Map<Long, BarSeriesManager> barSeriesManagers = new ConcurrentHashMap<>();

    /** ta4j indicators per instrument token. */
    private final Map<Long, Map<String, Indicator<Num>>> indicatorMap = new ConcurrentHashMap<>();

    /** Cached indicator values for fast reads. Key: "instrumentToken:indicatorKey". */
    private final Map<String, BigDecimal> indicatorCache = new ConcurrentHashMap<>();

    /** Instruments with registered conditions or active strategies (for lazy calc). */
    private final Set<Long> activeInstruments = ConcurrentHashMap.newKeySet();

    public IndicatorService(
            IndicatorConfig indicatorConfig,
            HistoricalDataSeeder historicalDataSeeder,
            ApplicationEventPublisher applicationEventPublisher) {
        this.indicatorConfig = indicatorConfig;
        this.historicalDataSeeder = historicalDataSeeder;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Initializes BarSeries and indicators for all configured instruments.
     * Called on application startup.
     */
    @jakarta.annotation.PostConstruct
    public void initialize() {
        if (!indicatorConfig.isEnabled()) {
            log.info("Indicator service is disabled");
            return;
        }

        for (InstrumentIndicatorConfig config : indicatorConfig.getInstruments()) {
            initializeInstrument(config);
        }

        log.info(
                "Indicator service initialized for {} instruments with {} total indicator entries",
                barSeriesManagers.size(),
                indicatorMap.values().stream().mapToInt(Map::size).sum());
    }

    /**
     * Processes a TickEvent by updating the bar series and, if a bar completes,
     * recalculating all indicators for that instrument.
     *
     * <p>Runs at @Order(2) in the TickEvent listener chain (after TickProcessor cache,
     * before position/strategy evaluation).
     */
    @EventListener
    @Order(2)
    public void onTick(TickEvent tickEvent) {
        Tick tick = tickEvent.getTick();
        Long token = tick.getInstrumentToken();

        BarSeriesManager barSeriesManager = barSeriesManagers.get(token);
        if (barSeriesManager == null) {
            return; // Not a tracked instrument
        }

        boolean barCompleted = barSeriesManager.processTick(tick.getLastPrice(), tick.getVolume(), tick.getTimestamp());

        if (barCompleted) {
            updateIndicatorCache(token);
            publishIndicatorUpdate(token);
        }
    }

    /**
     * Gets the current value of a specific indicator for an instrument.
     *
     * @param instrumentToken the instrument token
     * @param indicatorType   the indicator type
     * @param period          the indicator period (null to use default)
     * @param field           the field for multi-output indicators (null for single-output)
     * @return the current indicator value, or null if not available
     */
    public BigDecimal getIndicatorValue(
            Long instrumentToken, IndicatorType indicatorType, Integer period, String field) {
        String key =
                instrumentToken + ":" + IndicatorFactory.buildKey(indicatorType, period != null ? period : 0, field);
        return indicatorCache.get(key);
    }

    /**
     * Gets all indicator values for an instrument as a snapshot.
     *
     * @param instrumentToken the instrument token
     * @return map of indicator key -> current value
     */
    public Map<String, BigDecimal> getIndicatorSnapshot(Long instrumentToken) {
        String prefix = instrumentToken + ":";
        Map<String, BigDecimal> snapshot = new LinkedHashMap<>();

        for (Map.Entry<String, BigDecimal> entry : indicatorCache.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                String indicatorKey = entry.getKey().substring(prefix.length());
                snapshot.put(indicatorKey, entry.getValue());
            }
        }

        return snapshot;
    }

    /**
     * Gets bar data for an instrument (for charting).
     *
     * @param instrumentToken the instrument token
     * @param maxBars         maximum number of bars to return
     * @return list of bar snapshots, ordered oldest-first
     */
    public List<BarSeriesManager.BarSnapshot> getBarData(Long instrumentToken, int maxBars) {
        BarSeriesManager barSeriesManager = barSeriesManagers.get(instrumentToken);
        if (barSeriesManager == null) {
            return Collections.emptyList();
        }
        return barSeriesManager.getBarSnapshots(maxBars);
    }

    /**
     * Returns information about all tracked instruments.
     */
    public List<TrackedInstrument> getTrackedInstruments() {
        return barSeriesManagers.values().stream()
                .map(mgr -> new TrackedInstrument(
                        mgr.getInstrumentToken(),
                        mgr.getTradingSymbol(),
                        mgr.getBarDuration().toSeconds(),
                        mgr.getBarCount(),
                        indicatorMap
                                .getOrDefault(mgr.getInstrumentToken(), Map.of())
                                .size()))
                .collect(Collectors.toList());
    }

    /**
     * Registers an instrument for lazy indicator calculation. Only instruments
     * in the active set have their indicators recalculated on each bar completion.
     * If no instruments are active, all tracked instruments are calculated (default).
     *
     * @param instrumentToken the instrument to activate
     */
    public void registerActiveInstrument(Long instrumentToken) {
        activeInstruments.add(instrumentToken);
    }

    /**
     * Unregisters an instrument from lazy calculation.
     */
    public void unregisterActiveInstrument(Long instrumentToken) {
        activeInstruments.remove(instrumentToken);
    }

    /** Returns true if the instrument is being tracked for indicators. */
    public boolean isTracked(Long instrumentToken) {
        return barSeriesManagers.containsKey(instrumentToken);
    }

    /** Returns all indicator metadata for UI rendering. */
    public List<IndicatorMetadata> getAvailableIndicators() {
        return IndicatorMetadata.allMetadata();
    }

    // ---- Internal ----

    private void initializeInstrument(InstrumentIndicatorConfig config) {
        BarSeriesManager barSeriesManager = new BarSeriesManager(config);
        barSeriesManagers.put(config.getInstrumentToken(), barSeriesManager);

        // Seed historical data
        historicalDataSeeder.seed(barSeriesManager, config);

        // Create ta4j indicators attached to the BarSeries
        Map<String, Indicator<Num>> indicators =
                IndicatorFactory.createIndicators(barSeriesManager.getBarSeries(), config.getIndicators());
        indicatorMap.put(config.getInstrumentToken(), indicators);

        log.info(
                "Initialized {} indicators for {} (token={}, barDuration={})",
                indicators.size(),
                config.getTradingSymbol(),
                config.getInstrumentToken(),
                config.getBarDuration());
    }

    private void updateIndicatorCache(Long instrumentToken) {
        // Lazy calculation: if activeInstruments is non-empty, only calculate for active ones
        if (!activeInstruments.isEmpty() && !activeInstruments.contains(instrumentToken)) {
            return;
        }

        Map<String, Indicator<Num>> indicators = indicatorMap.get(instrumentToken);
        if (indicators == null) {
            return;
        }

        BarSeriesManager barSeriesManager = barSeriesManagers.get(instrumentToken);
        BarSeries series = barSeriesManager.getBarSeries();

        barSeriesManager.getReadLock().lock();
        try {
            int lastIndex = series.getEndIndex();
            if (lastIndex < 0) {
                return;
            }

            for (Map.Entry<String, Indicator<Num>> entry : indicators.entrySet()) {
                String key = entry.getKey();
                Indicator<Num> indicator = entry.getValue();

                try {
                    Num value = indicator.getValue(lastIndex);
                    BigDecimal decimal = BigDecimal.valueOf(value.doubleValue()).setScale(4, RoundingMode.HALF_UP);

                    String cacheKey = instrumentToken + ":" + key;
                    indicatorCache.put(cacheKey, decimal);
                } catch (Exception e) {
                    log.debug("Insufficient data for indicator {} on token {}", key, instrumentToken);
                }
            }
        } finally {
            barSeriesManager.getReadLock().unlock();
        }
    }

    private void publishIndicatorUpdate(Long instrumentToken) {
        Map<String, BigDecimal> snapshot = getIndicatorSnapshot(instrumentToken);
        if (snapshot.isEmpty()) {
            return;
        }

        BarSeriesManager barSeriesManager = barSeriesManagers.get(instrumentToken);
        applicationEventPublisher.publishEvent(
                new IndicatorUpdateEvent(this, instrumentToken, barSeriesManager.getTradingSymbol(), snapshot));
    }

    /**
     * Immutable record describing a tracked instrument for API responses.
     */
    public record TrackedInstrument(
            Long instrumentToken, String tradingSymbol, long barDurationSeconds, int barCount, int indicatorCount) {}
}
