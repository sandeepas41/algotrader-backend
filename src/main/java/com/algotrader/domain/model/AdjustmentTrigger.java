package com.algotrader.domain.model;

import com.algotrader.domain.enums.TriggerCondition;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * Defines the condition that must be met for an adjustment rule to fire.
 *
 * <p>The condition compares the monitored metric (delta, premium, DTE, IV)
 * against threshold values. For BETWEEN conditions, both threshold and threshold2
 * are used to define the range.
 */
@Data
@Builder
public class AdjustmentTrigger {

    private TriggerCondition condition;
    private BigDecimal threshold;

    /** Second threshold, only used for BETWEEN condition. */
    private BigDecimal threshold2;
}
