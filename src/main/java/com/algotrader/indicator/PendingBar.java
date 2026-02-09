package com.algotrader.indicator;

import com.algotrader.domain.model.Tick;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * Accumulates tick data into an OHLCV bar that is not yet complete.
 *
 * <p>As ticks arrive, open/high/low/close/volume are updated. Once the bar
 * duration elapses, the pending bar is "completed" (added to the BarSeries)
 * and a new PendingBar is created for the next interval.
 *
 * <p>Thread safety: PendingBar instances are accessed only under the write lock
 * of the corresponding BarSeriesManager, so no internal synchronization is needed.
 */
@Getter
public class PendingBar {

    private final LocalDateTime openTime;
    private LocalDateTime closeTime;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private long volume;

    public PendingBar(LocalDateTime openTime) {
        this.openTime = openTime;
        this.closeTime = openTime;
    }

    /**
     * Incorporates a tick into this pending bar.
     *
     * @param tick the incoming market tick
     */
    public void update(Tick tick) {
        BigDecimal price = tick.getLastPrice();

        if (open == null) {
            open = price;
            high = price;
            low = price;
        }

        if (price.compareTo(high) > 0) {
            high = price;
        }
        if (price.compareTo(low) < 0) {
            low = price;
        }

        close = price;
        closeTime = tick.getTimestamp();
        volume += tick.getVolume();
    }

    /** Returns true if at least one tick has been received. */
    public boolean hasData() {
        return open != null;
    }
}
