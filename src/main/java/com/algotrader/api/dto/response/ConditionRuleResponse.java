package com.algotrader.api.dto.response;

import com.algotrader.domain.enums.ConditionActionType;
import com.algotrader.domain.enums.ConditionOperator;
import com.algotrader.domain.enums.ConditionRuleStatus;
import com.algotrader.domain.enums.EvaluationMode;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.indicator.IndicatorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * REST API response DTO for a condition rule.
 * Returned by all CRUD endpoints on ConditionController.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConditionRuleResponse {

    private Long id;
    private String name;
    private String description;
    private Long instrumentToken;
    private String tradingSymbol;
    private String underlying;
    private IndicatorType indicatorType;
    private Integer indicatorPeriod;
    private String indicatorField;
    private ConditionOperator operator;
    private BigDecimal thresholdValue;
    private BigDecimal secondaryThreshold;
    private EvaluationMode evaluationMode;
    private ConditionActionType actionType;
    private StrategyType strategyType;
    private String strategyConfig;
    private ConditionRuleStatus status;
    private Integer maxTriggers;
    private Integer triggerCount;
    private Integer cooldownMinutes;
    private LocalDateTime lastTriggeredAt;
    private LocalTime validFrom;
    private LocalTime validUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
