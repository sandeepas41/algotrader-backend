package com.algotrader.entity;

import com.algotrader.domain.enums.MorphPlanStatus;
import com.algotrader.domain.enums.StrategyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the morph_plans table (WAL for morph operations).
 *
 * <p>Every morph operation is persisted as a plan before execution begins.
 * This WAL (Write-Ahead Log) approach ensures that if the application crashes
 * mid-morph, the operation can be inspected and recovered on restart.
 *
 * <p>The planDetails column stores the full serialized MorphExecutionPlan as JSON,
 * enabling complete reconstruction of the morph steps during recovery.
 */
@Entity
@Table(name = "morph_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MorphPlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_strategy_id", length = 36, nullable = false)
    private String sourceStrategyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_strategy_type", columnDefinition = "varchar(50)")
    private StrategyType sourceStrategyType;

    /** JSON array of target StrategyType values. */
    @Column(name = "target_types", columnDefinition = "TEXT")
    private String targetTypes;

    /** Full serialized MorphExecutionPlan as JSON for crash recovery. */
    @Column(name = "plan_details", columnDefinition = "TEXT")
    private String planDetails;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(50)")
    private MorphPlanStatus status;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
