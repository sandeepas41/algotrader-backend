package com.algotrader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the daily_pnl table.
 * End-of-day P&L summary with trade statistics, used by the dashboard and reporting pages.
 * One row per trading day. Populated by the EOD reconciliation job.
 */
@Entity
@Table(name = "daily_pnl")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyPnlEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private LocalDate date;

    @Column(name = "realized_pnl", precision = 15, scale = 2)
    private BigDecimal realizedPnl;

    @Column(name = "unrealized_pnl", precision = 15, scale = 2)
    private BigDecimal unrealizedPnl;

    @Column(name = "total_trades")
    private int totalTrades;

    @Column(name = "winning_trades")
    private int winningTrades;

    @Column(name = "losing_trades")
    private int losingTrades;

    @Column(name = "max_drawdown", precision = 15, scale = 2)
    private BigDecimal maxDrawdown;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
