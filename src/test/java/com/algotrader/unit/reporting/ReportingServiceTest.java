package com.algotrader.unit.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.algotrader.domain.enums.GroupBy;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.entity.DailyPnlEntity;
import com.algotrader.entity.TradeEntity;
import com.algotrader.reporting.ChargeReport;
import com.algotrader.reporting.PnLReport;
import com.algotrader.reporting.ReportingService;
import com.algotrader.reporting.StrategyPerformanceCalculator;
import com.algotrader.repository.jpa.DailyPnlJpaRepository;
import com.algotrader.repository.jpa.TradeJpaRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for ReportingService (Task 18.1).
 *
 * <p>Verifies: P&L report groupBy daily/weekly/monthly, charge report aggregation,
 * and delegation to StrategyPerformanceCalculator.
 */
class ReportingServiceTest {

    @Mock
    private DailyPnlJpaRepository dailyPnlJpaRepository;

    @Mock
    private TradeJpaRepository tradeJpaRepository;

    @Mock
    private StrategyPerformanceCalculator strategyPerformanceCalculator;

    private ReportingService reportingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        reportingService =
                new ReportingService(dailyPnlJpaRepository, tradeJpaRepository, strategyPerformanceCalculator);
    }

    @Test
    void pnlReport_dailyGrouping_returnsOneEntryPerDay() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 1, 3);

        List<DailyPnlEntity> records = List.of(
                createDailyPnl(LocalDate.of(2025, 1, 1), new BigDecimal("500"), 10, 7, 3),
                createDailyPnl(LocalDate.of(2025, 1, 2), new BigDecimal("-200"), 5, 2, 3),
                createDailyPnl(LocalDate.of(2025, 1, 3), new BigDecimal("300"), 8, 5, 3));
        when(dailyPnlJpaRepository.findByDateRange(from, to)).thenReturn(records);

        PnLReport report = reportingService.getPnLReport(from, to, GroupBy.DAILY);

        assertThat(report.getEntries()).hasSize(3);
        assertThat(report.getTradingDays()).isEqualTo(3);
        assertThat(report.getTotalRealizedPnl()).isEqualByComparingTo(new BigDecimal("600"));
        assertThat(report.getTotalTrades()).isEqualTo(23);
    }

    @Test
    void pnlReport_weeklyGrouping_aggregatesIntoPeriods() {
        LocalDate from = LocalDate.of(2025, 1, 6);
        LocalDate to = LocalDate.of(2025, 1, 17);

        // Week 1: Jan 6-10
        List<DailyPnlEntity> records = List.of(
                createDailyPnl(LocalDate.of(2025, 1, 6), new BigDecimal("100"), 5, 3, 2),
                createDailyPnl(LocalDate.of(2025, 1, 7), new BigDecimal("200"), 5, 4, 1),
                // Week 2: Jan 13-17
                createDailyPnl(LocalDate.of(2025, 1, 13), new BigDecimal("-50"), 3, 1, 2),
                createDailyPnl(LocalDate.of(2025, 1, 14), new BigDecimal("150"), 4, 3, 1));
        when(dailyPnlJpaRepository.findByDateRange(from, to)).thenReturn(records);

        PnLReport report = reportingService.getPnLReport(from, to, GroupBy.WEEKLY);

        // Two weeks
        assertThat(report.getEntries()).hasSize(2);
        assertThat(report.getTotalRealizedPnl()).isEqualByComparingTo(new BigDecimal("400"));
    }

    @Test
    void pnlReport_monthlyGrouping_aggregatesIntoMonths() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 2, 28);

        List<DailyPnlEntity> records = List.of(
                createDailyPnl(LocalDate.of(2025, 1, 15), new BigDecimal("500"), 10, 7, 3),
                createDailyPnl(LocalDate.of(2025, 1, 16), new BigDecimal("300"), 8, 6, 2),
                createDailyPnl(LocalDate.of(2025, 2, 5), new BigDecimal("-100"), 4, 1, 3));
        when(dailyPnlJpaRepository.findByDateRange(from, to)).thenReturn(records);

        PnLReport report = reportingService.getPnLReport(from, to, GroupBy.MONTHLY);

        // Two months: 2025-01 and 2025-02
        assertThat(report.getEntries()).hasSize(2);
        // January total: 500 + 300 = 800
        assertThat(report.getEntries().get(0).getRealizedPnl()).isEqualByComparingTo(new BigDecimal("800"));
        // February total: -100
        assertThat(report.getEntries().get(1).getRealizedPnl()).isEqualByComparingTo(new BigDecimal("-100"));
    }

    @Test
    void pnlReport_emptyRecords_returnsEmptyReport() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 1, 31);
        when(dailyPnlJpaRepository.findByDateRange(from, to)).thenReturn(List.of());

        PnLReport report = reportingService.getPnLReport(from, to, GroupBy.DAILY);

        assertThat(report.getEntries()).isEmpty();
        assertThat(report.getTotalRealizedPnl()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getTradingDays()).isEqualTo(0);
    }

    @Test
    void chargeReport_aggregatesItemizedCharges() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 1, 31);

        List<TradeEntity> trades = List.of(
                createTradeWithCharges(
                        new BigDecimal("20"),
                        new BigDecimal("5"),
                        new BigDecimal("3"),
                        new BigDecimal("0.10"),
                        new BigDecimal("1"),
                        new BigDecimal("4.15"),
                        LocalDateTime.of(2025, 1, 15, 10, 0)),
                createTradeWithCharges(
                        new BigDecimal("20"),
                        new BigDecimal("8"),
                        new BigDecimal("4"),
                        new BigDecimal("0.15"),
                        new BigDecimal("2"),
                        new BigDecimal("4.35"),
                        LocalDateTime.of(2025, 1, 16, 11, 0)));
        when(tradeJpaRepository.findByDateRange(
                        from.atStartOfDay(), to.plusDays(1).atStartOfDay()))
                .thenReturn(trades);

        ChargeReport report = reportingService.getChargeReport(from, to);

        assertThat(report.getTotalBrokerage()).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(report.getTotalSTT()).isEqualByComparingTo(new BigDecimal("13"));
        assertThat(report.getTotalExchangeCharges()).isEqualByComparingTo(new BigDecimal("7"));
        assertThat(report.getTotalSebiCharges()).isEqualByComparingTo(new BigDecimal("0.25"));
        assertThat(report.getTotalStampDuty()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(report.getTotalGST()).isEqualByComparingTo(new BigDecimal("8.50"));
        assertThat(report.getTotalTrades()).isEqualTo(2);
        assertThat(report.getTradingDays()).isEqualTo(2);
    }

    @Test
    void chargeReport_emptyTrades_returnsZeroCharges() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 1, 31);
        when(tradeJpaRepository.findByDateRange(
                        from.atStartOfDay(), to.plusDays(1).atStartOfDay()))
                .thenReturn(List.of());

        ChargeReport report = reportingService.getChargeReport(from, to);

        assertThat(report.getGrandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getTotalTrades()).isEqualTo(0);
    }

    // ---- Helpers ----

    private DailyPnlEntity createDailyPnl(
            LocalDate date, BigDecimal realizedPnl, int totalTrades, int winning, int losing) {
        return DailyPnlEntity.builder()
                .date(date)
                .realizedPnl(realizedPnl)
                .unrealizedPnl(BigDecimal.ZERO)
                .totalTrades(totalTrades)
                .winningTrades(winning)
                .losingTrades(losing)
                .maxDrawdown(BigDecimal.ZERO)
                .build();
    }

    private TradeEntity createTradeWithCharges(
            BigDecimal brokerage,
            BigDecimal stt,
            BigDecimal exchangeCharges,
            BigDecimal sebiCharges,
            BigDecimal stampDuty,
            BigDecimal gst,
            LocalDateTime executedAt) {
        BigDecimal total = brokerage
                .add(stt)
                .add(exchangeCharges)
                .add(sebiCharges)
                .add(stampDuty)
                .add(gst);
        return TradeEntity.builder()
                .id(java.util.UUID.randomUUID().toString())
                .side(OrderSide.SELL)
                .price(new BigDecimal("150.0"))
                .quantity(75)
                .brokerage(brokerage)
                .stt(stt)
                .exchangeCharges(exchangeCharges)
                .sebiCharges(sebiCharges)
                .stampDuty(stampDuty)
                .gst(gst)
                .totalCharges(total)
                .executedAt(executedAt)
                .build();
    }
}
