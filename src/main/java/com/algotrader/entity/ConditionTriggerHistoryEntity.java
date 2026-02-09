package com.algotrader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the condition_trigger_history table.
 *
 * <p>Audit trail for every condition rule trigger. Records the indicator state
 * at trigger time, the comparison that fired, and the action taken. Used by
 * the frontend to display trigger history and by analytics to evaluate rule
 * effectiveness.
 */
@Entity
@Table(name = "condition_trigger_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConditionTriggerHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "rule_name")
    private String ruleName;

    @Column(name = "indicator_type", columnDefinition = "varchar(50)")
    private String indicatorType;

    @Column(name = "indicator_value")
    private BigDecimal indicatorValue;

    @Column(name = "threshold_value")
    private BigDecimal thresholdValue;

    @Column(name = "operator", columnDefinition = "varchar(50)")
    private String operator;

    @Column(name = "action_taken", columnDefinition = "varchar(50)")
    private String actionTaken;

    @Column(name = "strategy_id")
    private String strategyId;

    @Column(name = "triggered_at", nullable = false)
    private LocalDateTime triggeredAt;
}
