package com.algotrader.domain.model;

import com.algotrader.domain.enums.ConditionActionType;
import com.algotrader.domain.enums.ConditionRuleStatus;
import com.algotrader.domain.enums.LogicOperator;
import com.algotrader.domain.enums.StrategyType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Domain model for a composite condition rule that combines multiple individual
 * condition rules with AND/OR logic.
 *
 * <p>Used for complex scenarios where multiple indicators must align before deploying
 * a strategy (e.g., "SuperTrend bullish AND RSI > 50").
 *
 * <p>Composite rules reference child rule IDs rather than embedding conditions,
 * allowing reuse of individual condition rules across multiple composites.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompositeConditionRule {

    private Long id;
    private String name;
    private String description;

    private LogicOperator logicOperator;
    private List<Long> childRuleIds;

    // Action on trigger
    private ConditionActionType actionType;
    private StrategyType strategyType;
    private String strategyConfig;

    private ConditionRuleStatus status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
