package com.algotrader.api.controller;

import com.algotrader.api.dto.response.TradeSummaryResponse;
import com.algotrader.domain.enums.GroupBy;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.entity.TradeEntity;
import com.algotrader.reporting.ChargeReport;
import com.algotrader.reporting.PnLReport;
import com.algotrader.reporting.ReportingService;
import com.algotrader.reporting.StrategyPerformanceReport;
import com.algotrader.reporting.TaxReport;
import com.algotrader.reporting.TaxReportGenerator;
import com.algotrader.repository.jpa.TradeJpaRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for P&L reports, strategy performance, charge reports, tax reports,
 * and trade summaries.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/reports/pnl} -- P&L report grouped by day/week/month</li>
 *   <li>{@code GET /api/reports/strategy/{id}/performance} -- strategy performance metrics</li>
 *   <li>{@code GET /api/reports/charges} -- itemized charge breakdown</li>
 *   <li>{@code GET /api/reports/tax} -- tax report for financial year</li>
 *   <li>{@code GET /api/trades/summary} -- trade summary for date range</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ReportsController {

    private final ReportingService reportingService;
    private final TaxReportGenerator taxReportGenerator;
    private final TradeJpaRepository tradeJpaRepository;

    public ReportsController(
            ReportingService reportingService,
            TaxReportGenerator taxReportGenerator,
            TradeJpaRepository tradeJpaRepository) {
        this.reportingService = reportingService;
        this.taxReportGenerator = taxReportGenerator;
        this.tradeJpaRepository = tradeJpaRepository;
    }

    @GetMapping("/reports/pnl")
    public PnLReport getPnLReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAILY") GroupBy groupBy) {
        return reportingService.getPnLReport(from, to, groupBy);
    }

    @GetMapping("/reports/strategy/{id}/performance")
    public StrategyPerformanceReport getStrategyPerformance(@PathVariable String id) {
        return reportingService.getStrategyPerformance(id);
    }

    @GetMapping("/reports/charges")
    public ChargeReport getChargeReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportingService.getChargeReport(from, to);
    }

    @GetMapping("/reports/tax")
    public TaxReport getTaxReport(@RequestParam String financialYear) {
        return taxReportGenerator.generate(financialYear);
    }

    /**
     * Trade summary endpoint for the frontend trade summary cards.
     */
    @GetMapping("/trades/summary")
    public TradeSummaryResponse getTradeSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<TradeEntity> trades = tradeJpaRepository.findByDateRange(
                from.atStartOfDay(), to.plusDays(1).atStartOfDay());

        int buyTrades = 0;
        int sellTrades = 0;
        BigDecimal totalVolume = BigDecimal.ZERO;
        BigDecimal totalCharges = BigDecimal.ZERO;
        BigDecimal netPnL = BigDecimal.ZERO;

        for (TradeEntity trade : trades) {
            if (trade.getSide() == OrderSide.BUY) {
                buyTrades++;
            } else {
                sellTrades++;
            }
            if (trade.getPrice() != null) {
                totalVolume = totalVolume.add(trade.getPrice().multiply(BigDecimal.valueOf(trade.getQuantity())));
            }
            if (trade.getTotalCharges() != null) {
                totalCharges = totalCharges.add(trade.getTotalCharges());
            }
            if (trade.getPnl() != null) {
                netPnL = netPnL.add(trade.getPnl());
            }
        }

        return TradeSummaryResponse.builder()
                .totalTrades(trades.size())
                .buyTrades(buyTrades)
                .sellTrades(sellTrades)
                .totalVolume(totalVolume)
                .totalCharges(totalCharges)
                .netPnL(netPnL)
                .build();
    }
}
