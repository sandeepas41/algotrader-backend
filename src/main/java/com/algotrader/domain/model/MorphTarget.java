package com.algotrader.domain.model;

import com.algotrader.domain.enums.StrategyType;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Defines a single target strategy within a morph request.
 *
 * <p>Specifies the target strategy type, which legs to retain from the source,
 * which new legs to open, and any strategy-specific configuration overrides.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MorphTarget {

    private StrategyType strategyType;

    /** Leg identifiers to retain from source (e.g., "SELL_CE", "BUY_PE"). */
    private List<String> retainedLegs;

    /** New legs to open for this target strategy. */
    private List<NewLegDefinition> newLegs;

    /** Strategy-specific configuration overrides. */
    private Map<String, Object> parameters;
}
