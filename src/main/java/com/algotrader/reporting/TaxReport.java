package com.algotrader.reporting;

import com.algotrader.domain.enums.IncomeClassification;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;

/**
 * Tax report for an Indian financial year (April 1 to March 31).
 *
 * <p>Key fields for ITR filing:
 * <ul>
 *   <li>turnover: F&O turnover = sum of absolute P&L per trade (Section 44AB)</li>
 *   <li>taxAuditRequired: true if turnover exceeds Rs 10 crore (digital) or Rs 2 crore (non-digital)</li>
 *   <li>presumptiveTaxEligible: true if turnover below Rs 2 crore (Section 44AD)</li>
 *   <li>incomeClassification: F&O = NON_SPECULATIVE_BUSINESS</li>
 * </ul>
 */
@Data
@Builder
public class TaxReport {

    private String financialYear;
    private LocalDate from;
    private LocalDate to;
    private BigDecimal turnover;
    private BigDecimal grossPnL;
    private BigDecimal netPnL;
    private BigDecimal totalCharges;
    private BigDecimal totalSTTPaid;
    private IncomeClassification incomeClassification;
    private boolean taxAuditRequired;
    private boolean presumptiveTaxEligible;
    private int totalTradingDays;
    private int totalTradeCount;
}
