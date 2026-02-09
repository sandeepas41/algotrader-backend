package com.algotrader.entity;

import com.algotrader.domain.enums.StrategyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
 * JPA entity for the morph_history table (strategy lineage tracking).
 *
 * <p>Records parent-child relationships when a strategy is morphed into
 * one or more target strategies. Enables tracing the full evolution of
 * a trading idea and calculating cumulative P&L across morphs.
 */
@Entity
@Table(name = "morph_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MorphHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_strategy_id", length = 36, nullable = false)
    private String parentStrategyId;

    @Column(name = "child_strategy_id", length = 36)
    private String childStrategyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "parent_strategy_type", columnDefinition = "varchar(50)")
    private StrategyType parentStrategyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "child_strategy_type", columnDefinition = "varchar(50)")
    private StrategyType childStrategyType;

    @Column(name = "morph_reason", columnDefinition = "TEXT")
    private String morphReason;

    /** P&L of the parent strategy at the time of the morph. */
    @Column(name = "parent_pnl_at_morph", precision = 15, scale = 2)
    private BigDecimal parentPnlAtMorph;

    @Column(name = "morphed_at")
    private LocalDateTime morphedAt;
}
