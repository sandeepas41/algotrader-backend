package com.algotrader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * JPA entity for the order_fills table.
 * Tracks individual partial fills within an order (1 order → N fills).
 */
@Entity
@Table(name = "order_fills")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderFillEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "order_id", length = 36)
    private String orderId;

    @Column(name = "instrument_token")
    private Long instrumentToken;

    @Column(name = "trading_symbol", length = 50)
    private String tradingSymbol;

    private int quantity;

    @Column(precision = 15, scale = 2)
    private BigDecimal price;

    @Column(precision = 10, scale = 2)
    private BigDecimal fees;

    @Column(name = "exchange_order_id", length = 100)
    private String exchangeOrderId;

    @Column(name = "filled_at")
    private LocalDateTime filledAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
