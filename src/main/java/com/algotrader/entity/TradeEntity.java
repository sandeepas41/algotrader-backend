package com.algotrader.entity;

import com.algotrader.domain.enums.OrderSide;
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
 * JPA entity for the trades table.
 * Stores executed trades with itemized charge breakdown for tax reporting.
 */
@Entity
@Table(name = "trades")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "order_id", length = 36)
    private String orderId;

    @Column(name = "instrument_token")
    private Long instrumentToken;

    @Column(name = "trading_symbol", length = 50)
    private String tradingSymbol;

    @Column(length = 10)
    private String exchange;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(10)")
    private OrderSide side;

    private int quantity;

    @Column(precision = 15, scale = 2)
    private BigDecimal price;

    // Itemized charge breakdown (flat columns, mapped to ChargeBreakdown VO by mapper)
    @Column(precision = 10, scale = 2)
    private BigDecimal brokerage;

    @Column(precision = 10, scale = 2)
    private BigDecimal stt;

    @Column(name = "exchange_charges", precision = 10, scale = 2)
    private BigDecimal exchangeCharges;

    @Column(name = "sebi_charges", precision = 10, scale = 2)
    private BigDecimal sebiCharges;

    @Column(name = "stamp_duty", precision = 10, scale = 2)
    private BigDecimal stampDuty;

    @Column(precision = 10, scale = 2)
    private BigDecimal gst;

    @Column(name = "total_charges", precision = 10, scale = 2)
    private BigDecimal totalCharges;

    @Column(precision = 15, scale = 2)
    private BigDecimal pnl;

    @Column(name = "strategy_id", length = 36)
    private String strategyId;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
