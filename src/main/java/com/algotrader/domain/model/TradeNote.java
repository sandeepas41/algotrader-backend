package com.algotrader.domain.model;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * A journal entry for post-trade analysis and learning.
 *
 * <p>Traders can attach notes to strategies or specific runs to record observations,
 * market conditions, and lessons learned. Tags enable filtering and categorization.
 * Star rating (1-5) provides a quick quality assessment of the trade.
 *
 * <p>Used by the trade journal UI and tax reporting (trade notes can document
 * the rationale behind trades for audit purposes).
 */
@Data
@Builder
public class TradeNote {

    private Long id;

    /** Strategy this note is about. Null for general market notes. */
    private String strategyId;

    /** Specific run within the strategy. Null for strategy-level notes. */
    private String runId;

    private String note;

    /** Comma-separated tags for categorization (e.g., "adjustment,delta-hedge,nifty"). */
    private String tags;

    /** Quality rating 1-5. Null if not rated. */
    private Integer starRating;

    /** Market condition at the time (e.g., "trending", "range-bound", "volatile"). */
    private String marketCondition;

    private LocalDateTime createdAt;
}
