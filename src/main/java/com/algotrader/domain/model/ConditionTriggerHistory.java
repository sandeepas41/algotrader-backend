package com.algotrader.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Domain model for a condition trigger history entry.
 *
 * <p>Every time a condition rule fires, a history record is persisted for audit
 * and analysis. Records the indicator value at trigger time, the threshold that
 * was crossed, the action taken, and the strategy deployed (if any).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConditionTriggerHistory {

    private Long id;
    private Long ruleId;
    private String ruleName;
    private String indicatorType;
    private BigDecimal indicatorValue;
    private BigDecimal thresholdValue;
    private String operator;
    private String actionTaken;
    /** Strategy ID deployed by this trigger (null for ALERT_ONLY). */
    private String strategyId;

    private LocalDateTime triggeredAt;
}
