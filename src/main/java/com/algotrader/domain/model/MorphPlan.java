package com.algotrader.domain.model;

import com.algotrader.domain.enums.MorphPlanStatus;
import com.algotrader.domain.enums.StrategyType;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Domain model for a morph execution plan (WAL entry).
 *
 * <p>Represents the persisted plan for a morph operation, including the
 * source strategy, target types, serialized execution plan, and lifecycle
 * timestamps. Corresponds to the morph_plans table via MorphPlanEntity.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MorphPlan {

    private Long id;
    private String sourceStrategyId;
    private StrategyType sourceStrategyType;

    /** JSON array of target StrategyType values. */
    private String targetTypes;

    /** Full serialized MorphExecutionPlan as JSON. */
    private String planDetails;

    private MorphPlanStatus status;
    private String reason;
    private LocalDateTime createdAt;
    private LocalDateTime executedAt;
    private LocalDateTime completedAt;
    private String errorMessage;
}
