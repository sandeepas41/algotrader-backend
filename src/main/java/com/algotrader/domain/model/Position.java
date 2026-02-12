package com.algotrader.domain.model;

import com.algotrader.domain.enums.PositionType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An open or closed position in the trading account.
 *
 * <p>Primary storage is Redis (real-time access); H2 stores historical snapshots.
 * Most fields originate from Kite API's position data, but Greeks are calculated
 * locally using Black-Scholes (Kite does NOT provide Greeks).
 *
 * <p>Quantity is signed: positive = LONG, negative = SHORT. This convention
 * matches Kite's netQuantity field and simplifies P&L calculations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Position {

    private String id;
    private Long instrumentToken;
    private String tradingSymbol;
    private String exchange;

    /** Signed quantity: positive = long, negative = short. From Kite's netQuantity. */
    private int quantity;

    private BigDecimal averagePrice;
    private BigDecimal lastPrice;
    private BigDecimal unrealizedPnl;
    private BigDecimal realizedPnl;

    /** Mark-to-market P&L — the daily settlement value from Kite. */
    private BigDecimal m2m;

    /** Quantity carried forward from previous day. */
    private int overnightQuantity;

    /** Calculated via Black-Scholes, NOT from Kite. Recalculated on every tick. */
    private Greeks greeks;

    /** Strategy this position belongs to. Null for manually traded positions. */
    private String strategyId;

    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private LocalDateTime lastUpdated;

    /** Derived from signed quantity. */
    public PositionType getType() {
        return quantity >= 0 ? PositionType.LONG : PositionType.SHORT;
    }
}
