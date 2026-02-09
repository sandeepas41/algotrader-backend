package com.algotrader.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * P&L for a segment of a strategy run between two adjustments.
 *
 * <p>Each StrategyRun is divided into segments: the first segment starts at entry,
 * subsequent segments start after each adjustment, and the last segment ends at exit.
 * This enables analysis of which adjustments helped or hurt overall P&L.
 */
@Data
@Builder
public class PnLSegment {

    private int segmentNumber;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal pnl;

    /** What caused this segment to end (e.g., "Delta exceeded 0.30", "Time-based roll"). */
    private String adjustmentTrigger;
}
