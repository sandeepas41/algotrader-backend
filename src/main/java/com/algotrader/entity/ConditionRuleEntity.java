package com.algotrader.entity;

import com.algotrader.domain.enums.ConditionActionType;
import com.algotrader.domain.enums.ConditionOperator;
import com.algotrader.domain.enums.ConditionRuleStatus;
import com.algotrader.domain.enums.EvaluationMode;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.indicator.IndicatorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the condition_rules table.
 *
 * <p>Stores condition rules that the ConditionEngine evaluates against real-time
 * indicator values. When a rule's condition is met, it triggers an action such as
 * deploying a strategy, arming a strategy, or sending an alert.
 *
 * <p>Each rule watches a specific indicator on a specific instrument and compares
 * against a threshold using a configurable operator. Crossing operators (CROSSES_ABOVE,
 * CROSSES_BELOW) detect transitions between evaluations.
 */
@Entity
@Table(name = "condition_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConditionRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    // What to watch
    @Column(name = "instrument_token", nullable = false)
    private Long instrumentToken;

    @Column(name = "trading_symbol", nullable = false)
    private String tradingSymbol;

    @Column(name = "underlying")
    private String underlying;

    // Indicator condition
    @Enumerated(EnumType.STRING)
    @Column(name = "indicator_type", nullable = false, columnDefinition = "varchar(50)")
    private IndicatorType indicatorType;

    @Column(name = "indicator_period")
    private Integer indicatorPeriod;

    @Column(name = "indicator_field")
    private String indicatorField;

    @Enumerated(EnumType.STRING)
    @Column(name = "operator", nullable = false, columnDefinition = "varchar(50)")
    private ConditionOperator operator;

    @Column(name = "threshold_value", nullable = false)
    private BigDecimal thresholdValue;

    @Column(name = "secondary_threshold")
    private BigDecimal secondaryThreshold;

    // Evaluation frequency
    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_mode", nullable = false, columnDefinition = "varchar(50)")
    private EvaluationMode evaluationMode;

    // Action on trigger
    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, columnDefinition = "varchar(50)")
    private ConditionActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", columnDefinition = "varchar(50)")
    private StrategyType strategyType;

    @Column(name = "strategy_config", columnDefinition = "JSON")
    private String strategyConfig;

    // State
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "varchar(50)")
    private ConditionRuleStatus status;

    @Column(name = "max_triggers")
    private Integer maxTriggers;

    @Builder.Default
    @Column(name = "trigger_count")
    private Integer triggerCount = 0;

    @Builder.Default
    @Column(name = "cooldown_minutes")
    private Integer cooldownMinutes = 30;

    @Column(name = "last_triggered_at")
    private LocalDateTime lastTriggeredAt;

    // Time window
    @Column(name = "valid_from")
    private LocalTime validFrom;

    @Column(name = "valid_until")
    private LocalTime validUntil;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
