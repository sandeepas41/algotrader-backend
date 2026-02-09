package com.algotrader.unit.reporting;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.ReportsController;
import com.algotrader.domain.enums.GroupBy;
import com.algotrader.domain.enums.IncomeClassification;
import com.algotrader.exception.GlobalExceptionHandler;
import com.algotrader.reporting.ChargeReport;
import com.algotrader.reporting.PnLReport;
import com.algotrader.reporting.PnLReportEntry;
import com.algotrader.reporting.ReportingService;
import com.algotrader.reporting.StrategyPerformanceReport;
import com.algotrader.reporting.TaxReport;
import com.algotrader.reporting.TaxReportGenerator;
import com.algotrader.repository.jpa.TradeJpaRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for ReportsController (Task 18.2).
 *
 * <p>Verifies: P&L report, strategy performance, charge report, tax report,
 * and trade summary endpoints.
 */
class ReportsControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ReportingService reportingService;

    @Mock
    private TaxReportGenerator taxReportGenerator;

    @Mock
    private TradeJpaRepository tradeJpaRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReportsController controller = new ReportsController(reportingService, taxReportGenerator, tradeJpaRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getPnLReport_returnsReport() throws Exception {
        PnLReport report = PnLReport.builder()
                .from(LocalDate.of(2025, 1, 1))
                .to(LocalDate.of(2025, 1, 31))
                .groupBy(GroupBy.DAILY)
                .entries(List.of(PnLReportEntry.builder()
                        .period("2025-01-15")
                        .realizedPnl(new BigDecimal("500"))
                        .build()))
                .totalRealizedPnl(new BigDecimal("500"))
                .totalUnrealizedPnl(BigDecimal.ZERO)
                .tradingDays(1)
                .totalTrades(10)
                .build();

        when(reportingService.getPnLReport(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31), GroupBy.DAILY))
                .thenReturn(report);

        mockMvc.perform(get("/api/reports/pnl")
                        .param("from", "2025-01-01")
                        .param("to", "2025-01-31")
                        .param("groupBy", "DAILY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRealizedPnl").value(500))
                .andExpect(jsonPath("$.tradingDays").value(1))
                .andExpect(jsonPath("$.entries[0].period").value("2025-01-15"));
    }

    @Test
    void getStrategyPerformance_returnsReport() throws Exception {
        StrategyPerformanceReport report = StrategyPerformanceReport.builder()
                .strategyId("STR-1")
                .totalRuns(10)
                .winRate(new BigDecimal("60.0000"))
                .profitFactor(new BigDecimal("2.50"))
                .build();

        when(reportingService.getStrategyPerformance("STR-1")).thenReturn(report);

        mockMvc.perform(get("/api/reports/strategy/STR-1/performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyId").value("STR-1"))
                .andExpect(jsonPath("$.totalRuns").value(10))
                .andExpect(jsonPath("$.profitFactor").value(2.50));
    }

    @Test
    void getChargeReport_returnsReport() throws Exception {
        ChargeReport report = ChargeReport.builder()
                .from(LocalDate.of(2025, 1, 1))
                .to(LocalDate.of(2025, 1, 31))
                .totalBrokerage(new BigDecimal("400"))
                .totalSTT(new BigDecimal("130"))
                .totalExchangeCharges(new BigDecimal("70"))
                .totalSebiCharges(new BigDecimal("2.50"))
                .totalStampDuty(new BigDecimal("30"))
                .totalGST(new BigDecimal("85"))
                .grandTotal(new BigDecimal("717.50"))
                .tradingDays(20)
                .totalTrades(100)
                .build();

        when(reportingService.getChargeReport(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31)))
                .thenReturn(report);

        mockMvc.perform(get("/api/reports/charges").param("from", "2025-01-01").param("to", "2025-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grandTotal").value(717.50))
                .andExpect(jsonPath("$.tradingDays").value(20))
                .andExpect(jsonPath("$.totalTrades").value(100));
    }

    @Test
    void getTaxReport_returnsReport() throws Exception {
        TaxReport report = TaxReport.builder()
                .financialYear("2024-25")
                .from(LocalDate.of(2024, 4, 1))
                .to(LocalDate.of(2025, 3, 31))
                .turnover(new BigDecimal("5000000"))
                .grossPnL(new BigDecimal("200000"))
                .netPnL(new BigDecimal("185000"))
                .totalCharges(new BigDecimal("15000"))
                .totalSTTPaid(new BigDecimal("3000"))
                .incomeClassification(IncomeClassification.NON_SPECULATIVE_BUSINESS)
                .taxAuditRequired(false)
                .presumptiveTaxEligible(true)
                .totalTradingDays(200)
                .totalTradeCount(1500)
                .build();

        when(taxReportGenerator.generate("2024-25")).thenReturn(report);

        mockMvc.perform(get("/api/reports/tax").param("financialYear", "2024-25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financialYear").value("2024-25"))
                .andExpect(jsonPath("$.taxAuditRequired").value(false))
                .andExpect(jsonPath("$.presumptiveTaxEligible").value(true))
                .andExpect(jsonPath("$.incomeClassification").value("NON_SPECULATIVE_BUSINESS"));
    }

    @Test
    void getTradeSummary_returnsAggregates() throws Exception {
        when(tradeJpaRepository.findByDateRange(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/trades/summary").param("from", "2025-01-01").param("to", "2025-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTrades").value(0))
                .andExpect(jsonPath("$.netPnL").value(0));
    }
}
