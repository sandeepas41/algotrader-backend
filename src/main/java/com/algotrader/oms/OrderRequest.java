package com.algotrader.oms;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * Encapsulates all parameters needed to place an order through the {@link OrderRouter}.
 *
 * <p>This is the single input type for the OMS. Strategy engines, manual UI submissions,
 * and kill switch operations all construct an OrderRequest to route through the validation
 * pipeline (idempotency check -> kill switch check -> risk validation -> queue/execute).
 *
 * <p>The correlationId groups related orders (e.g., all legs of a multi-leg entry)
 * for tracing and reconciliation. The strategyId links the order to its owning strategy
 * (null for manual orders).
 */
@Data
@Builder
public class OrderRequest {

    /** Kite instrument token for the contract being traded. */
    private long instrumentToken;

    /** Kite trading symbol (e.g., "NIFTY24FEB22000CE"). */
    private String tradingSymbol;

    /** Exchange (e.g., "NFO", "NSE", "BSE"). */
    private String exchange;

    private OrderSide side;
    private OrderType type;

    /** Kite product type: "NRML" (overnight), "MIS" (intraday margin). */
    @Builder.Default
    private String product = "NRML";

    /** Order quantity in lots * lot size (absolute value). */
    private int quantity;

    /** Limit price. Required for LIMIT and SL order types. */
    private BigDecimal price;

    /** Trigger price. Required for SL and SL_M order types. */
    private BigDecimal triggerPrice;

    /** Strategy this order belongs to. Null for manually placed orders. */
    private String strategyId;

    /** Groups related orders (e.g., all legs of a multi-leg entry). */
    private String correlationId;
}
