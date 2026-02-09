package com.algotrader.entity;

import com.algotrader.domain.enums.AdjustmentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the adjustment_rules table.
 * Stores configurable rules for automatic position adjustment within a strategy.
 * Trigger conditions and action parameters are stored as JSON for flexibility.
 */
@Entity
@Table(name = "adjustment_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdjustmentRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_id", length = 36)
    private String strategyId;

    @Column(length = 100)
    private String name;

    /** What metric this rule monitors (DELTA, PREMIUM, TIME, IV). */
    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", columnDefinition = "varchar(50)")
    private AdjustmentType adjustmentType;

    /** JSON-serialized AdjustmentTrigger (condition + threshold(s)). */
    @Column(name = "trigger_config", columnDefinition = "JSON")
    private String triggerConfig;

    /** JSON-serialized AdjustmentAction (action type + parameters). */
    @Column(name = "action_config", columnDefinition = "JSON")
    private String actionConfig;

    /** Lower number = higher priority. Determines evaluation order. */
    private int priority;

    /** Minutes to wait after triggering before this rule can fire again. */
    @Column(name = "cooldown_minutes")
    private int cooldownMinutes;

    /** Maximum trigger count. Null = unlimited. */
    @Column(name = "max_triggers")
    private Integer maxTriggers;

    @Column(name = "trigger_count")
    private int triggerCount;

    @Column(name = "last_triggered_at")
    private LocalDateTime lastTriggeredAt;

    private boolean enabled;
}
