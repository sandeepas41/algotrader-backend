package com.algotrader.domain.model;

import com.algotrader.domain.enums.AmendmentStatus;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * A trading order (pending, active, or completed).
 *
 * <p>Primary storage is Redis (real-time tracking); H2 stores historical records.
 * Orders flow through the OrderRouter → OrderQueue → BrokerGateway pipeline.
 * The correlationId links related orders (e.g., multi-leg strategy entry),
 * while parentOrderId links bracket/cover order chains.
 *
 * <p>An order may have multiple partial fills tracked via OrderFill entities.
 * The averageFillPrice is the VWAP across all fills.
 */
@Data
@Builder
public class Order {

    private String id;

    /** Kite's order ID, assigned after successful placement. Null while PENDING. */
    private String brokerOrderId;

    /** Groups related orders (e.g., all legs of a multi-leg entry). */
    private String correlationId;

    private Long instrumentToken;
    private String tradingSymbol;
    private String exchange;
    private OrderSide side;
    private OrderType type;

    /** Kite product type: "NRML" (overnight), "MIS" (intraday margin). */
    private String product;

    private int quantity;

    /** Limit price. Required for LIMIT and SL order types. */
    private BigDecimal price;

    /** Trigger price. Required for SL and SL_M order types. */
    private BigDecimal triggerPrice;

    private OrderStatus status;
    private int filledQuantity;

    /** Volume-weighted average price across all partial fills. */
    private BigDecimal averageFillPrice;

    /** Strategy this order belongs to. Null for manually placed orders. */
    private String strategyId;

    /** Parent order ID for bracket/cover order chains. */
    private String parentOrderId;

    private String rejectionReason;

    /** Current amendment lifecycle state. Defaults to NONE. */
    @Builder.Default
    private AmendmentStatus amendmentStatus = AmendmentStatus.NONE;

    /** Reason from broker when an amendment is rejected. */
    private String amendmentRejectReason;

    private LocalDateTime placedAt;
    private LocalDateTime updatedAt;
}
