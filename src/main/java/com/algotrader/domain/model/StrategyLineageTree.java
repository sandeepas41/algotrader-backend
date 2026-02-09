package com.algotrader.domain.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents the full lineage tree for a strategy -- its ancestors
 * (parent, grandparent, ...) and descendants (children, grandchildren, ...).
 *
 * <p>Used to trace the complete transformation history of a position
 * through multiple morphs and to calculate cumulative P&L across the chain.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyLineageTree {

    private String strategyId;

    /** Lineage records tracing back to the original strategy. */
    private List<StrategyLineage> ancestors;

    /** Lineage records for all child strategies (recursive). */
    private List<StrategyLineage> descendants;
}
