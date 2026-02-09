package com.algotrader.reporting;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * A single period entry in a P&L report (e.g., one day, one week, or one month).
 * Contains aggregated P&L and trade count for that period.
 */
@Data
@Builder
public class PnLReportEntry {

    /** The period label (date for DAILY, week-start for WEEKLY, YYYY-MM for MONTHLY). */
    private String period;

    private BigDecimal realizedPnl;
    private BigDecimal unrealizedPnl;
    private int tradeCount;
    private int winningTrades;
    private int losingTrades;
    private BigDecimal maxDrawdown;
}
