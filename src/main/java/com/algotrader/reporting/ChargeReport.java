package com.algotrader.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;

/**
 * Itemized charge breakdown report over a date range.
 * Aggregates all 6 charge components across trades for cost analysis and tax reporting.
 */
@Data
@Builder
public class ChargeReport {

    private LocalDate from;
    private LocalDate to;
    private BigDecimal totalBrokerage;
    private BigDecimal totalSTT;
    private BigDecimal totalExchangeCharges;
    private BigDecimal totalSebiCharges;
    private BigDecimal totalStampDuty;
    private BigDecimal totalGST;
    private BigDecimal grandTotal;
    private int tradingDays;
    private int totalTrades;
}
