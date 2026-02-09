package com.algotrader.domain.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Domain model for a morph (strategy transformation) request.
 *
 * <p>Specifies the source strategy to morph and one or more target strategies
 * to create. Each target can retain legs from the source and/or open new legs.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MorphRequest {

    private String sourceStrategyId;
    private List<MorphTarget> targets;

    /** Whether to copy entry prices from retained legs to new strategies. */
    private boolean copyEntryPrices;

    /** Whether to immediately arm new strategies after creation. */
    private boolean autoArm;

    /** Override lot sizing (null = inherit from source). */
    private Integer overrideLots;

    /** Audit trail reason for this morph. */
    private String reason;
}
