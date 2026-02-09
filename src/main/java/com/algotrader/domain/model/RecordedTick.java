package com.algotrader.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * A recorded market tick in the 88-byte binary format.
 *
 * <p>Fields stored: timestamp(8) + instrumentToken(8) + lastPrice(8) + open(8) +
 * high(8) + low(8) + close(8) + volume(8) + oi(8) + oiChange(8) + receivedAtNanos(8) = 88 bytes.
 *
 * <p>Used by TickRecorder to buffer ticks before flushing to binary files,
 * and by TickReplayEngine to load ticks for replay.
 */
@Data
@Builder
public class RecordedTick {

    /** Kite instrument token (unique identifier for the instrument). */
    private long instrumentToken;

    /** Last traded price. */
    private BigDecimal lastPrice;

    /** Day's opening price. */
    private BigDecimal open;

    /** Day's highest price. */
    private BigDecimal high;

    /** Day's lowest price. */
    private BigDecimal low;

    /** Previous day's closing price. */
    private BigDecimal close;

    /** Cumulative volume traded today. */
    private long volume;

    /** Open interest — total outstanding contracts. */
    private BigDecimal oi;

    /** OI change from previous day. */
    private BigDecimal oiChange;

    /** Exchange timestamp of this tick. */
    private LocalDateTime timestamp;

    /** System.nanoTime() when the tick was received from Kite WebSocket. For latency analysis. */
    private long receivedAtNanos;
}
