package com.algotrader.domain.model;

import com.algotrader.domain.enums.StrategyType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tracks strategy lineage through morphs (strategy type transformations).
 *
 * <p>When a strategy morphs (e.g., iron condor -> bull put spread + straddle),
 * one or more lineage records are created linking the source (parent) and
 * target (child) strategies. This enables tracing the full evolution of a
 * trading idea across multiple strategy types and calculating cumulative P&L.
 *
 * <p>Maps to the strategy_lineage table.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyLineage {

    private Long id;
    private String parentStrategyId;
    private String childStrategyId;
    private StrategyType parentStrategyType;
    private StrategyType childStrategyType;

    /** Why the morph was triggered (e.g., "Delta exceeded threshold", "Manual conversion"). */
    private String morphReason;

    /** P&L of the parent strategy at the time of the morph. */
    private BigDecimal parentPnlAtMorph;

    private LocalDateTime morphedAt;
}
