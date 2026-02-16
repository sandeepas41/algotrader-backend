package com.algotrader.entity;

import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.enums.TradingMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * JPA entity for the strategies table.
 * Stores strategy configurations, lifecycle state, and cumulative P&L.
 * Legs and adjustment rules are stored in separate tables (strategy_legs, adjustment_rules)
 * and loaded by the mapper/service layer.
 */
@Entity
@Table(name = "strategies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(50)")
    private StrategyType type;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(50)")
    private StrategyStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trading_mode", columnDefinition = "varchar(10)")
    private TradingMode tradingMode;

    @Column(length = 20)
    private String underlying;

    private LocalDate expiry;

    /** Strategy-type-specific parameters as JSON (e.g., StraddleConfig, IronCondorConfig). */
    @Column(columnDefinition = "CLOB")
    private String config;

    @Column(name = "total_pnl", precision = 15, scale = 2)
    private BigDecimal totalPnl;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "deployed_at")
    private LocalDateTime deployedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}
