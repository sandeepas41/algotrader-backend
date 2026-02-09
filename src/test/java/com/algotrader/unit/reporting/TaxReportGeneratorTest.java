package com.algotrader.unit.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.algotrader.domain.enums.IncomeClassification;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.entity.DailyPnlEntity;
import com.algotrader.entity.TradeEntity;
import com.algotrader.reporting.TaxReport;
import com.algotrader.reporting.TaxReportGenerator;
import com.algotrader.repository.jpa.DailyPnlJpaRepository;
import com.algotrader.repository.jpa.TradeJpaRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for TaxReportGenerator (Task 18.2).
 *
 * <p>Verifies: financial year parsing, F&O turnover calculation, audit threshold check,
 * presumptive tax eligibility, income classification, and P&L totals.
 */
class TaxReportGeneratorTest {

    @Mock
    private DailyPnlJpaRepository dailyPnlJpaRepository;

    @Mock
    private TradeJpaRepository tradeJpaRepository;

    private TaxReportGenerator taxReportGenerator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        taxReportGenerator = new TaxReportGenerator(dailyPnlJpaRepository, tradeJpaRepository);
    }

    @Test
    void parseFinancialYear_correctDates() {
        LocalDate[] dates = taxReportGenerator.parseFinancialYear("2024-25");

        assertThat(dates[0]).isEqualTo(LocalDate.of(2024, Month.APRIL, 1));
        assertThat(dates[1]).isEqualTo(LocalDate.of(2025, Month.MARCH, 31));
    }

    @Test
    void turnover_calculatedAsAbsoluteTradeValues() {
        List<TradeEntity> trades = List.of(
                createTrade(new BigDecimal("150"), 75, OrderSide.BUY),
                createTrade(new BigDecimal("160"), 75, OrderSide.SELL),
                createTrade(new BigDecimal("200"), 50, OrderSide.SELL));

        BigDecimal turnover = taxReportGenerator.calculateFnOTurnover(trades);

        // |150*75| + |160*75| + |200*50| = 11250 + 12000 + 10000 = 33250
        assertThat(turnover).isEqualByComparingTo(new BigDecimal("33250.00"));
    }

    @Test
    void taxAuditRequired_whenTurnoverExceeds10Crore() {
        LocalDate from = LocalDate.of(2024, 4, 1);
        LocalDate to = LocalDate.of(2025, 3, 31);

        // Create a trade with enough value to exceed Rs 10 crore
        TradeEntity largeTrade = createTrade(new BigDecimal("100000"), 1100, OrderSide.SELL);
        when(tradeJpaRepository.findByDateRange(
                        from.atStartOfDay(), to.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(largeTrade));
        when(dailyPnlJpaRepository.findByDateRange(from, to)).thenReturn(List.of());

        TaxReport report = taxReportGenerator.generate("2024-25");

        // 100000 * 1100 = 110,000,000 > 100,000,000
        assertThat(report.isTaxAuditRequired()).isTrue();
        assertThat(report.isPresumptiveTaxEligible()).isFalse();
    }

    @Test
    void presumptiveTaxEligible_whenTurnoverBelow2Crore() {
        LocalDate from = LocalDate.of(2024, 4, 1);
        LocalDate to = LocalDate.of(2025, 3, 31);

        TradeEntity smallTrade = createTrade(new BigDecimal("150"), 75, OrderSide.BUY);
        when(tradeJpaRepository.findByDateRange(
                        from.atStartOfDay(), to.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(smallTrade));
        when(dailyPnlJpaRepository.findByDateRange(from, to)).thenReturn(List.of());

        TaxReport report = taxReportGenerator.generate("2024-25");

        // 150 * 75 = 11,250 < 20,000,000
        assertThat(report.isTaxAuditRequired()).isFalse();
        assertThat(report.isPresumptiveTaxEligible()).isTrue();
    }

    @Test
    void incomeClassification_alwaysNonSpeculative() {
        LocalDate from = LocalDate.of(2024, 4, 1);
        LocalDate to = LocalDate.of(2025, 3, 31);
        when(tradeJpaRepository.findByDateRange(
                        from.atStartOfDay(), to.plusDays(1).atStartOfDay()))
                .thenReturn(List.of());
        when(dailyPnlJpaRepository.findByDateRange(from, to)).thenReturn(List.of());

        TaxReport report = taxReportGenerator.generate("2024-25");

        assertThat(report.getIncomeClassification()).isEqualTo(IncomeClassification.NON_SPECULATIVE_BUSINESS);
    }

    @Test
    void generate_includesCorrectPnLTotals() {
        LocalDate from = LocalDate.of(2024, 4, 1);
        LocalDate to = LocalDate.of(2025, 3, 31);

        List<DailyPnlEntity> dailyRecords = List.of(
                DailyPnlEntity.builder()
                        .date(LocalDate.of(2024, 6, 1))
                        .realizedPnl(new BigDecimal("5000"))
                        .build(),
                DailyPnlEntity.builder()
                        .date(LocalDate.of(2024, 7, 1))
                        .realizedPnl(new BigDecimal("-2000"))
                        .build());

        TradeEntity t1 = TradeEntity.builder()
                .id("t1")
                .price(new BigDecimal("150"))
                .quantity(75)
                .side(OrderSide.BUY)
                .totalCharges(new BigDecimal("50"))
                .stt(new BigDecimal("5"))
                .executedAt(LocalDateTime.of(2024, 6, 1, 10, 0))
                .build();
        TradeEntity t2 = TradeEntity.builder()
                .id("t2")
                .price(new BigDecimal("160"))
                .quantity(75)
                .side(OrderSide.SELL)
                .totalCharges(new BigDecimal("60"))
                .stt(new BigDecimal("8"))
                .executedAt(LocalDateTime.of(2024, 7, 1, 10, 0))
                .build();

        when(dailyPnlJpaRepository.findByDateRange(from, to)).thenReturn(dailyRecords);
        when(tradeJpaRepository.findByDateRange(
                        from.atStartOfDay(), to.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(t1, t2));

        TaxReport report = taxReportGenerator.generate("2024-25");

        assertThat(report.getGrossPnL()).isEqualByComparingTo(new BigDecimal("3000"));
        assertThat(report.getTotalCharges()).isEqualByComparingTo(new BigDecimal("110"));
        assertThat(report.getNetPnL()).isEqualByComparingTo(new BigDecimal("2890"));
        assertThat(report.getTotalSTTPaid()).isEqualByComparingTo(new BigDecimal("13"));
        assertThat(report.getTotalTradingDays()).isEqualTo(2);
        assertThat(report.getTotalTradeCount()).isEqualTo(2);
    }

    @Test
    void generate_setsCorrectDateRange() {
        LocalDate from = LocalDate.of(2024, 4, 1);
        LocalDate to = LocalDate.of(2025, 3, 31);
        when(tradeJpaRepository.findByDateRange(
                        from.atStartOfDay(), to.plusDays(1).atStartOfDay()))
                .thenReturn(List.of());
        when(dailyPnlJpaRepository.findByDateRange(from, to)).thenReturn(List.of());

        TaxReport report = taxReportGenerator.generate("2024-25");

        assertThat(report.getFinancialYear()).isEqualTo("2024-25");
        assertThat(report.getFrom()).isEqualTo(LocalDate.of(2024, 4, 1));
        assertThat(report.getTo()).isEqualTo(LocalDate.of(2025, 3, 31));
    }

    // ---- Helpers ----

    private TradeEntity createTrade(BigDecimal price, int quantity, OrderSide side) {
        return TradeEntity.builder()
                .id(java.util.UUID.randomUUID().toString())
                .price(price)
                .quantity(quantity)
                .side(side)
                .executedAt(LocalDateTime.of(2024, 6, 15, 10, 0))
                .build();
    }
}
