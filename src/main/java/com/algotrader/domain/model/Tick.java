package com.algotrader.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * Real-time market data tick received from Kite WebSocket.
 *
 * <p>Ticks are the primary input for all real-time processing: position P&L updates,
 * strategy evaluation, Greeks recalculation, and risk monitoring. The TickBuffer
 * maintains the last 100 ticks per instrument in a Caffeine cache for indicator calculations.
 *
 * <p>Depth data (buyDepth/sellDepth) is only available for instruments subscribed in
 * Kite's FULL mode. LTP-only subscriptions will have null depth fields.
 */
@Data
@Builder
public class Tick {

    private Long instrumentToken;
    private BigDecimal lastPrice;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;

    /** Previous day's closing price. Used for change% calculations. */
    private BigDecimal close;

    private long volume;

    /** Total buy quantity across all price levels in the order book. */
    private BigDecimal buyQuantity;

    /** Total sell quantity across all price levels in the order book. */
    private BigDecimal sellQuantity;

    /** Open interest — total outstanding contracts. Key indicator for options liquidity. */
    private BigDecimal oi;

    /** OI change from previous day's closing OI. Positive = new positions being created. */
    private BigDecimal oiChange;

    private LocalDateTime timestamp;

    /** Order book depth — 5 bid levels. Null if subscribed in LTP mode. */
    private List<DepthItem> buyDepth;

    /** Order book depth — 5 ask levels. Null if subscribed in LTP mode. */
    private List<DepthItem> sellDepth;
}
