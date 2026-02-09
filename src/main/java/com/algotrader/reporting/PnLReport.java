package com.algotrader.reporting;

import com.algotrader.domain.enums.GroupBy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * P&L report aggregated by day, week, or month over a date range.
 * Contains individual period entries and overall summary totals.
 */
@Data
@Builder
public class PnLReport {

    private LocalDate from;
    private LocalDate to;
    private GroupBy groupBy;
    private List<PnLReportEntry> entries;
    private BigDecimal totalRealizedPnl;
    private BigDecimal totalUnrealizedPnl;
    private int tradingDays;
    private int totalTrades;
}
