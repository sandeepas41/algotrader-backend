package com.algotrader.domain.model;

import com.algotrader.domain.enums.AdjustmentType;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * A configurable rule for automatic position adjustment within a strategy.
 *
 * <p>Each rule monitors a specific metric (delta, premium, time, IV) and fires
 * when the trigger condition is met. Rules have cooldown periods to prevent
 * rapid-fire adjustments and max trigger limits for safety.
 *
 * <p>Rules are evaluated in priority order (lower number = higher priority).
 * When multiple rules trigger simultaneously, only the highest-priority one fires.
 * The cooldown timer starts after each trigger and blocks re-evaluation until it expires.
 */
@Data
@Builder
public class AdjustmentRule {

    private Long id;
    private String strategyId;
    private String name;

    /** What metric this rule monitors (DELTA, PREMIUM, TIME, IV). */
    private AdjustmentType adjustmentType;

    /** When to fire: comparison operator + threshold(s). */
    private AdjustmentTrigger trigger;

    /** What to do when fired: action type + parameters. */
    private AdjustmentAction action;

    /** Lower number = higher priority. Determines evaluation order. */
    private int priority;

    /** Minutes to wait after triggering before this rule can fire again. */
    private int cooldownMinutes;

    /** Maximum number of times this rule can trigger. Null = unlimited. */
    private Integer maxTriggers;

    /** How many times this rule has triggered so far. */
    private int triggerCount;

    private LocalDateTime lastTriggeredAt;
    private boolean enabled;
}
