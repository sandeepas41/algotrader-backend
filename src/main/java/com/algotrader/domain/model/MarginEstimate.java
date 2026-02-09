package com.algotrader.domain.model;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * Result of a what-if margin estimation for a proposed trade or multi-leg strategy.
 *
 * <p>Returned by {@link com.algotrader.margin.MarginEstimator} to show the trader
 * how much margin is required, whether they can afford it, and (for multi-leg orders)
 * how much hedging benefit they receive.
 *
 * <p>For single-leg orders, hedgeBenefit/individualMarginSum/legCount are not set.
 * For multi-leg orders, hedgeBenefit shows the margin savings from portfolio netting
 * (e.g., iron condor margin is much less than the sum of 4 naked legs).
 */
@Data
@Builder
public class MarginEstimate {

    /** Total margin required for the proposed trade(s). */
    private BigDecimal requiredMargin;

    /** Sum of individual leg margins (only for multi-leg). */
    private BigDecimal individualMarginSum;

    /** Margin saved due to hedging: individualMarginSum - requiredMargin (only for multi-leg). */
    private BigDecimal hedgeBenefit;

    /** Hedge benefit as percentage of individual margins (only for multi-leg). */
    private BigDecimal hedgeBenefitPercent;

    /** Current available margin in the account. */
    private BigDecimal availableMargin;

    /** True if availableMargin >= requiredMargin. */
    private boolean sufficient;

    /** Amount of margin shortfall. Zero if sufficient. */
    private BigDecimal shortfall;

    /** Number of legs in the basket (only for multi-leg). */
    private int legCount;
}
