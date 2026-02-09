package com.algotrader.indicator;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

/**
 * Manages a single ta4j BarSeries for one instrument, handling tick-to-bar
 * aggregation with thread-safe read/write access.
 *
 * <p>Ticks are accumulated into a {@link PendingBar}. When the bar duration
 * elapses, the pending bar is finalized and added to the BarSeries. A
 * {@link ReadWriteLock} protects the BarSeries from concurrent modification
 * (ticks arrive on the event thread, reads happen on HTTP/scheduled threads).
 *
 * <p>The BarSeries has a configurable max bar count to limit memory usage.
 * Oldest bars are automatically evicted by ta4j when the limit is reached.
 */
public class BarSeriesManager {

    private static final Logger log = LoggerFactory.getLogger(BarSeriesManager.class);

    /** IST timezone for converting LocalDateTime to Instant for ta4j bars. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Getter
    private final Long instrumentToken;

    @Getter
    private final String tradingSymbol;

    @Getter
    private final Duration barDuration;

    private final BarSeries barSeries;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private PendingBar pendingBar;

    public BarSeriesManager(InstrumentIndicatorConfig config) {
        this.instrumentToken = config.getInstrumentToken();
        this.tradingSymbol = config.getTradingSymbol();
        this.barDuration = config.getBarDuration();
        this.barSeries = new BaseBarSeriesBuilder()
                .withName(config.getTradingSymbol())
                .withMaxBarCount(config.getMaxBars())
                .build();
    }

    /**
     * Processes a tick, accumulating it into the current pending bar.
     * Returns true if the tick completed a bar (bar was added to series).
     *
     * <p>Must be called under the write lock externally, or the caller
     * must guarantee single-threaded access.
     *
     * @param price     the last traded price
     * @param volume    the tick volume
     * @param timestamp the tick timestamp
     * @return true if a bar was completed and added to the series
     */
    public boolean processTick(BigDecimal price, long volume, LocalDateTime timestamp) {
        lock.writeLock().lock();
        try {
            if (pendingBar == null) {
                pendingBar = new PendingBar(timestamp);
            }

            // Accumulate tick
            pendingBar.update(createMinimalTick(price, volume, timestamp));

            // Check if bar duration has elapsed
            if (Duration.between(pendingBar.getOpenTime(), timestamp).compareTo(barDuration) >= 0) {
                if (pendingBar.hasData()) {
                    addBarToSeries(pendingBar);
                }
                pendingBar = new PendingBar(timestamp);
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Adds a historical bar directly to the series (used by HistoricalDataSeeder).
     */
    public void addHistoricalBar(
            LocalDateTime endTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
        lock.writeLock().lock();
        try {
            Instant instant = endTime.atZone(IST).toInstant();
            barSeries.addBar(barSeries
                    .barBuilder()
                    .timePeriod(barDuration)
                    .endTime(instant)
                    .openPrice(open.doubleValue())
                    .highPrice(high.doubleValue())
                    .lowPrice(low.doubleValue())
                    .closePrice(close.doubleValue())
                    .volume(volume)
                    .build());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the underlying ta4j BarSeries. Access to the series should be
     * protected by acquiring the read lock via {@link #getReadLock()}.
     */
    public BarSeries getBarSeries() {
        return barSeries;
    }

    /** Returns the read lock for safe concurrent reads of the BarSeries. */
    public java.util.concurrent.locks.Lock getReadLock() {
        return lock.readLock();
    }

    /** Returns the write lock (for external coordination if needed). */
    public java.util.concurrent.locks.Lock getWriteLock() {
        return lock.writeLock();
    }

    /** Returns the number of completed bars in the series. */
    public int getBarCount() {
        lock.readLock().lock();
        try {
            return barSeries.getBarCount();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns bar data as a list of simplified records for REST/WebSocket output.
     *
     * @param maxBars maximum number of bars to return (most recent)
     * @return list of bar data, ordered oldest-first
     */
    public List<BarSnapshot> getBarSnapshots(int maxBars) {
        lock.readLock().lock();
        try {
            if (barSeries.isEmpty()) {
                return Collections.emptyList();
            }

            List<BarSnapshot> bars = new ArrayList<>();
            int startIndex = Math.max(barSeries.getBeginIndex(), barSeries.getEndIndex() - maxBars + 1);

            for (int i = startIndex; i <= barSeries.getEndIndex(); i++) {
                Bar bar = barSeries.getBar(i);
                bars.add(new BarSnapshot(
                        LocalDateTime.ofInstant(bar.getEndTime(), IST),
                        BigDecimal.valueOf(bar.getOpenPrice().doubleValue()),
                        BigDecimal.valueOf(bar.getHighPrice().doubleValue()),
                        BigDecimal.valueOf(bar.getLowPrice().doubleValue()),
                        BigDecimal.valueOf(bar.getClosePrice().doubleValue()),
                        bar.getVolume().longValue()));
            }
            return bars;
        } finally {
            lock.readLock().unlock();
        }
    }

    private void addBarToSeries(PendingBar pending) {
        Instant instant = pending.getCloseTime().atZone(IST).toInstant();
        barSeries.addBar(barSeries
                .barBuilder()
                .timePeriod(barDuration)
                .endTime(instant)
                .openPrice(pending.getOpen().doubleValue())
                .highPrice(pending.getHigh().doubleValue())
                .lowPrice(pending.getLow().doubleValue())
                .closePrice(pending.getClose().doubleValue())
                .volume(pending.getVolume())
                .build());

        log.debug(
                "Bar completed for {} [O={} H={} L={} C={} V={}]",
                tradingSymbol,
                pending.getOpen(),
                pending.getHigh(),
                pending.getLow(),
                pending.getClose(),
                pending.getVolume());
    }

    private com.algotrader.domain.model.Tick createMinimalTick(BigDecimal price, long volume, LocalDateTime timestamp) {
        return com.algotrader.domain.model.Tick.builder()
                .instrumentToken(instrumentToken)
                .lastPrice(price)
                .volume(volume)
                .timestamp(timestamp)
                .build();
    }

    /**
     * Immutable snapshot of a completed bar for external consumption.
     */
    public record BarSnapshot(
            LocalDateTime timestamp, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {}
}
