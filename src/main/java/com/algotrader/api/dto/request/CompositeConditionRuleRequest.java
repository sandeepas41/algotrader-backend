package com.algotrader.api.dto.request;

import com.algotrader.domain.enums.ConditionActionType;
import com.algotrader.domain.enums.LogicOperator;
import com.algotrader.domain.enums.StrategyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * REST API request DTO for creating or updating a composite condition rule.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompositeConditionRuleRequest {

    @NotBlank
    private String name;

    private String description;

    @NotNull
    private LogicOperator logicOperator;

    @NotEmpty
    private List<Long> childRuleIds;

    @NotNull
    private ConditionActionType actionType;

    private StrategyType strategyType;
    private String strategyConfig;
}
