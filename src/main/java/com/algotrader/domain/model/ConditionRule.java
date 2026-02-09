package com.algotrader.domain.model;

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
 * Domain model for a condition rule that triggers actions when indicator thresholds are met.
 *
 * <p>A condition rule defines: IF indicator on instrument meets threshold THEN execute action.
 * The action can be deploying a strategy, arming a strategy, or sending an alert.
 *
 * <p>Rules are loaded into the ConditionEngine's in-memory cache at startup and refreshed
 * on CRUD operations. Each rule is keyed by instrument token for O(1) lookup on tick events.
 *
 * <p>Thread safety: Rule instances in the engine cache are accessed concurrently by tick
 * listeners and the scheduled interval evaluator. Mutable state (triggerCount, lastTriggeredAt,
 * status) is updated atomically within the ConditionEngine's synchronized trigger handler.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConditionRule {

    private Long id;
    private String name;
    private String description;

    // What to watch
    private Long instrumentToken;
    private String tradingSymbol;
    /** Underlying index (e.g., "NIFTY", "BANKNIFTY") for strategy deployment context. */
    private String underlying;

    // Indicator condition
    private IndicatorType indicatorType;
    /** Period parameter for the indicator (e.g., 14 for RSI(14), 20 for EMA(20)). */
    private Integer indicatorPeriod;
    /** Field for multi-output indicators (e.g., "upper", "lower", "middle" for Bollinger). */
    private String indicatorField;

    private ConditionOperator operator;
    private BigDecimal thresholdValue;
    /** Secondary threshold for BETWEEN/OUTSIDE operators. */
    private BigDecimal secondaryThreshold;

    // Evaluation frequency
    private EvaluationMode evaluationMode;

    // Action on trigger
    private ConditionActionType actionType;
    private StrategyType strategyType;
    /** JSON-serialized strategy parameters for auto-deployment. */
    private String strategyConfig;

    // State
    private ConditionRuleStatus status;
    /** Max times this rule can fire (null = unlimited). */
    private Integer maxTriggers;

    @Builder.Default
    private Integer triggerCount = 0;
    /** Cooldown between triggers in minutes. */
    @Builder.Default
    private Integer cooldownMinutes = 30;

    private LocalDateTime lastTriggeredAt;

    // Time window
    /** Time window start (e.g., 09:20). Rule is only evaluated within this window. */
    private LocalTime validFrom;
    /** Time window end (e.g., 15:00). */
    private LocalTime validUntil;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
