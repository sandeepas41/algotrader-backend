package com.algotrader.oms;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * Parameters for modifying an existing open order.
 *
 * <p>Only the non-null fields are applied to the order. At least one of
 * price, triggerPrice, or quantity must be provided. The OrderAmendmentService
 * validates these constraints before sending to the broker.
 *
 * <p>Kite allows modifying LIMIT price, SL trigger price, and quantity
 * on open orders. Order type and side cannot be changed — cancel and re-place instead.
 */
@Data
@Builder
public class OrderModification {

    /** New limit price. Null means no change. */
    private BigDecimal price;

    /** New trigger price (for SL/SL_M orders). Null means no change. */
    private BigDecimal triggerPrice;

    /** New quantity. Null means no change. Must be greater than already filled quantity. */
    private Integer quantity;
}
