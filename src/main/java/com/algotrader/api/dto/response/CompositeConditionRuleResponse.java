package com.algotrader.api.dto.response;

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
 * REST API response DTO for a composite condition rule.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompositeConditionRuleResponse {

    private Long id;
    private String name;
    private String description;
    private LogicOperator logicOperator;
    private List<Long> childRuleIds;
    private ConditionActionType actionType;
    private StrategyType strategyType;
    private String strategyConfig;
    private ConditionRuleStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
