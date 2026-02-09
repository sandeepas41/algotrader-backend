package com.algotrader.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * A single partial fill within an order. One Order can have multiple OrderFills (1:N)
 * when a large order or illiquid instrument results in fills at different price levels.
 *
 * <p>The Order's filledQuantity = sum of all OrderFill quantities.
 * The Order's averageFillPrice = VWAP across all OrderFills.
 */
@Data
@Builder
public class OrderFill {

    private String id;
    private String orderId;
    private Long instrumentToken;
    private String tradingSymbol;
    private int quantity;
    private BigDecimal price;
    private BigDecimal fees;

    /** Exchange-assigned order ID. Different from our internal order ID. */
    private String exchangeOrderId;

    private LocalDateTime filledAt;
}
