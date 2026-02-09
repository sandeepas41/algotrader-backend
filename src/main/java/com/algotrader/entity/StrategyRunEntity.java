package com.algotrader.entity;

import com.algotrader.domain.enums.StrategyRunStatus;
import com.algotrader.domain.enums.StrategyType;
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
 * JPA entity for the strategy_runs table.
 * Each run captures a single execution of a strategy from entry to exit,
 * with denormalized strategy info for historical queries (strategy may be deleted later).
 * P&L segments are stored as JSON for analysis of inter-adjustment performance.
 */
@Entity
@Table(name = "strategy_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyRunEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "strategy_id", length = 36)
    private String strategyId;

    /** Denormalized — strategy name at the time of this run. */
    @Column(name = "strategy_name", length = 100)
    private String strategyName;

    /** Denormalized — strategy type at the time of this run. */
    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", columnDefinition = "varchar(50)")
    private StrategyType strategyType;

    /** Denormalized — underlying symbol. */
    @Column(length = 20)
    private String underlying;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(50)")
    private StrategyRunStatus status;

    @Column(name = "entry_time")
    private LocalDateTime entryTime;

    @Column(name = "exit_time")
    private LocalDateTime exitTime;

    @Column(name = "entry_premium", precision = 15, scale = 2)
    private BigDecimal entryPremium;

    @Column(name = "exit_premium", precision = 15, scale = 2)
    private BigDecimal exitPremium;

    @Column(name = "gross_pnl", precision = 15, scale = 2)
    private BigDecimal grossPnl;

    @Column(name = "total_charges", precision = 15, scale = 2)
    private BigDecimal totalCharges;

    @Column(name = "net_pnl", precision = 15, scale = 2)
    private BigDecimal netPnl;

    @Column(name = "adjustment_count")
    private int adjustmentCount;

    @Column(name = "leg_count")
    private int legCount;

    @Column(name = "total_orders")
    private int totalOrders;

    /** Why this run ended (e.g., "Target profit 2.5%", "Max loss hit", "DTE < 1"). */
    @Column(name = "exit_reason")
    private String exitReason;

    /** JSON array of PnLSegment objects — P&L between adjustments. */
    @Column(name = "pnl_segments", columnDefinition = "JSON")
    private String pnlSegments;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
