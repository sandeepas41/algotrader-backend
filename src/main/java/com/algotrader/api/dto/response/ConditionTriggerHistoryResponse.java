package com.algotrader.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * REST API response DTO for a condition trigger history entry.
 * Returned by the trigger history endpoint on ConditionController.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConditionTriggerHistoryResponse {

    private Long id;
    private Long ruleId;
    private String ruleName;
    private String indicatorType;
    private BigDecimal indicatorValue;
    private BigDecimal thresholdValue;
    private String operator;
    private String actionTaken;
    private String strategyId;
    private LocalDateTime triggeredAt;
}
