package com.algotrader.api.dto.request;

import com.algotrader.domain.enums.ConditionActionType;
import com.algotrader.domain.enums.ConditionOperator;
import com.algotrader.domain.enums.EvaluationMode;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.indicator.IndicatorType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * REST API request DTO for creating or updating a condition rule.
 * Validated by Spring's @Valid annotation in the controller.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConditionRuleRequest {

    @NotBlank
    private String name;

    private String description;

    @NotNull
    private Long instrumentToken;

    @NotBlank
    private String tradingSymbol;

    private String underlying;

    @NotNull
    private IndicatorType indicatorType;

    private Integer indicatorPeriod;
    private String indicatorField;

    @NotNull
    private ConditionOperator operator;

    @NotNull
    private BigDecimal thresholdValue;

    private BigDecimal secondaryThreshold;

    @NotNull
    private EvaluationMode evaluationMode;

    @NotNull
    private ConditionActionType actionType;

    private StrategyType strategyType;
    private String strategyConfig;
    private Integer maxTriggers;
    private Integer cooldownMinutes;
    private LocalTime validFrom;
    private LocalTime validUntil;
}
