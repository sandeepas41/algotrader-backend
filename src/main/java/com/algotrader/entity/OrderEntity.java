package com.algotrader.entity;

import com.algotrader.domain.enums.AmendmentStatus;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the orders table.
 * H2 stores historical records; Redis is the primary real-time store.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "broker_order_id", length = 100)
    private String brokerOrderId;

    @Column(name = "correlation_id", length = 36)
    private String correlationId;

    @Column(name = "instrument_token")
    private Long instrumentToken;

    @Column(name = "trading_symbol", length = 50)
    private String tradingSymbol;

    @Column(length = 10)
    private String exchange;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(10)")
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", columnDefinition = "varchar(10)")
    private OrderType orderType;

    @Column(length = 10)
    private String product;

    private int quantity;

    @Column(precision = 15, scale = 2)
    private BigDecimal price;

    @Column(name = "trigger_price", precision = 15, scale = 2)
    private BigDecimal triggerPrice;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(50)")
    private OrderStatus status;

    @Column(name = "filled_quantity")
    private int filledQuantity;

    @Column(name = "average_fill_price", precision = 15, scale = 2)
    private BigDecimal averageFillPrice;

    @Column(name = "strategy_id", length = 36)
    private String strategyId;

    @Column(name = "parent_order_id", length = 36)
    private String parentOrderId;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "amendment_status", columnDefinition = "varchar(50)")
    private AmendmentStatus amendmentStatus;

    @Column(name = "amendment_reject_reason", columnDefinition = "TEXT")
    private String amendmentRejectReason;

    @Column(name = "placed_at")
    private LocalDateTime placedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
