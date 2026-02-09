package com.algotrader.reporting;

import com.algotrader.domain.enums.IncomeClassification;
import com.algotrader.entity.DailyPnlEntity;
import com.algotrader.entity.TradeEntity;
import com.algotrader.repository.jpa.DailyPnlJpaRepository;
import com.algotrader.repository.jpa.TradeJpaRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generates tax reports for Indian Income Tax Return filing.
 *
 * <p>Indian financial year runs from April 1 to March 31. F&O income is classified
 * as non-speculative business income under Section 43(5) of the Income Tax Act.
 *
 * <p>F&O turnover calculation for Section 44AB:
 * Turnover = sum of (absolute value of price * quantity) for each trade.
 * This determines whether tax audit is required:
 * <ul>
 *   <li>Turnover > Rs 10 crore (with >= 95% digital transactions): audit required</li>
 *   <li>Turnover <= Rs 2 crore: eligible for presumptive taxation (Section 44AD)</li>
 * </ul>
 */
@Service
public class TaxReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(TaxReportGenerator.class);

    /** Rs 10 crore threshold for tax audit (digital transactions). */
    private static final BigDecimal AUDIT_THRESHOLD = new BigDecimal("100000000");

    /** Rs 2 crore threshold for presumptive taxation. */
    private static final BigDecimal PRESUMPTIVE_THRESHOLD = new BigDecimal("20000000");

    private final DailyPnlJpaRepository dailyPnlJpaRepository;
    private final TradeJpaRepository tradeJpaRepository;

    public TaxReportGenerator(DailyPnlJpaRepository dailyPnlJpaRepository, TradeJpaRepository tradeJpaRepository) {
        this.dailyPnlJpaRepository = dailyPnlJpaRepository;
        this.tradeJpaRepository = tradeJpaRepository;
    }

    /**
     * Generates a tax report for the given Indian financial year.
     *
     * @param financialYear in format "2024-25" (April 2024 to March 2025)
     * @return tax report with turnover, P&L, and audit requirement flags
     */
    public TaxReport generate(String financialYear) {
        LocalDate[] dates = parseFinancialYear(financialYear);
        LocalDate from = dates[0];
        LocalDate to = dates[1];

        List<DailyPnlEntity> dailyRecords = dailyPnlJpaRepository.findByDateRange(from, to);
        List<TradeEntity> trades = tradeJpaRepository.findByDateRange(
                from.atStartOfDay(), to.plusDays(1).atStartOfDay());

        // F&O turnover = sum of absolute value of each trade's value
        BigDecimal turnover = calculateFnOTurnover(trades);

        // Total P&L from daily records
        BigDecimal grossPnL = dailyRecords.stream()
                .map(DailyPnlEntity::getRealizedPnl)
                .filter(pnl -> pnl != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Total charges from trades
        BigDecimal totalCharges = trades.stream()
                .filter(t -> t.getTotalCharges() != null)
                .map(TradeEntity::getTotalCharges)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal netPnL = grossPnL.subtract(totalCharges);

        // STT paid (deductible expense)
        BigDecimal totalSTT = trades.stream()
                .filter(t -> t.getStt() != null)
                .map(TradeEntity::getStt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // F&O is always non-speculative business income
        IncomeClassification classification = IncomeClassification.NON_SPECULATIVE_BUSINESS;

        boolean taxAuditRequired = turnover.compareTo(AUDIT_THRESHOLD) > 0;
        boolean presumptiveTaxEligible = turnover.compareTo(PRESUMPTIVE_THRESHOLD) <= 0;

        log.info(
                "Tax report generated: FY={}, turnover={}, netPnL={}, audit={}, presumptive={}",
                financialYear,
                turnover,
                netPnL,
                taxAuditRequired,
                presumptiveTaxEligible);

        return TaxReport.builder()
                .financialYear(financialYear)
                .from(from)
                .to(to)
                .turnover(turnover)
                .grossPnL(grossPnL)
                .netPnL(netPnL)
                .totalCharges(totalCharges)
                .totalSTTPaid(totalSTT)
                .incomeClassification(classification)
                .taxAuditRequired(taxAuditRequired)
                .presumptiveTaxEligible(presumptiveTaxEligible)
                .totalTradingDays(dailyRecords.size())
                .totalTradeCount(trades.size())
                .build();
    }

    /**
     * Calculates F&O turnover as sum of absolute trade values (price * quantity).
     * This is the definition used by Indian tax law for Section 44AB audit threshold.
     */
    public BigDecimal calculateFnOTurnover(List<TradeEntity> trades) {
        BigDecimal turnover = BigDecimal.ZERO;

        for (TradeEntity trade : trades) {
            if (trade.getPrice() != null) {
                BigDecimal tradeValue = trade.getPrice().multiply(BigDecimal.valueOf(trade.getQuantity()));
                turnover = turnover.add(tradeValue.abs());
            }
        }

        return turnover.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Parses "2024-25" to [April 1 2024, March 31 2025].
     */
    public LocalDate[] parseFinancialYear(String fy) {
        String[] parts = fy.split("-");
        int startYear = Integer.parseInt(parts[0]);
        LocalDate from = LocalDate.of(startYear, Month.APRIL, 1);
        LocalDate to = LocalDate.of(startYear + 1, Month.MARCH, 31);
        return new LocalDate[] {from, to};
    }
}
