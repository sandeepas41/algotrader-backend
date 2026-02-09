package com.algotrader.domain.model;

import com.algotrader.domain.enums.StrategyRunStatus;
import com.algotrader.domain.enums.StrategyType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * A single execution run of a strategy, capturing P&L from entry to exit.
 *
 * <p>A strategy can have multiple runs over its lifetime (e.g., re-armed after closing
 * on day 1, runs again on day 2). Each run tracks its own entry/exit times, P&L with
 * itemized charges, adjustment count, and P&L segments.
 *
 * <p>P&L segments divide the run into periods between adjustments, enabling analysis
 * of which adjustments helped or hurt. Segment 0 = entry to first adjustment,
 * segment 1 = first adjustment to second, etc.
 */
@Data
@Builder
public class StrategyRun {

    private String id;
    private String strategyId;

    /** Denormalized for historical queries (strategy may be deleted). */
    private String strategyName;

    /** Denormalized for historical queries. */
    private StrategyType strategyType;

    /** Denormalized for historical queries. */
    private String underlying;

    private StrategyRunStatus status;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;

    /** Total premium collected/paid at entry. */
    private BigDecimal entryPremium;

    /** Total premium at exit (for premium-based P&L calculation). */
    private BigDecimal exitPremium;

    private BigDecimal grossPnl;
    private BigDecimal totalCharges;
    private BigDecimal netPnl;
    private int adjustmentCount;
    private int legCount;
    private int totalOrders;

    /** Why this run ended (e.g., "Target profit 2.5%", "Max loss hit", "DTE < 1"). */
    private String exitReason;

    /** P&L broken down by segments between adjustments. */
    private List<PnLSegment> pnlSegments;
}
