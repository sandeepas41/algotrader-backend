package com.algotrader.domain.model;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.vo.ChargeBreakdown;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * An executed trade with itemized charges. Persisted to H2 for historical analysis.
 *
 * <p>Each trade represents a completed fill and includes a full ChargeBreakdown
 * (brokerage, STT, exchange charges, SEBI, stamp duty, GST). The charges are
 * calculated by ChargeCalculator using current regulatory rates.
 *
 * <p>Note: One order can produce multiple trades (partial fills), but each Trade
 * record represents the aggregate fill — not individual partial fills (those are OrderFill).
 */
@Data
@Builder
public class Trade {

    private String id;
    private String orderId;
    private Long instrumentToken;
    private String tradingSymbol;
    private String exchange;
    private OrderSide side;
    private int quantity;
    private BigDecimal price;

    /** Itemized transaction charges. See ChargeBreakdown for component details. */
    private ChargeBreakdown charges;

    /** Realized P&L from this trade. Null for opening trades (no P&L until position is closed). */
    private BigDecimal pnl;

    private String strategyId;
    private LocalDateTime executedAt;
}
