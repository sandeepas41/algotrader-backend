package com.algotrader.unit.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.algotrader.domain.enums.StrategyRunStatus;
import com.algotrader.domain.model.StrategyRun;
import com.algotrader.mapper.StrategyRunMapper;
import com.algotrader.reporting.StrategyPerformanceCalculator;
import com.algotrader.reporting.StrategyPerformanceReport;
import com.algotrader.repository.jpa.StrategyRunJpaRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for StrategyPerformanceCalculator (Task 18.1).
 *
 * <p>Verifies: win rate, profit factor, max drawdown, Sharpe ratio,
 * average holding time, MFE/MAE, empty report, and total charges.
 */
class StrategyPerformanceCalculatorTest {

    @Mock
    private StrategyRunJpaRepository strategyRunJpaRepository;

    @Mock
    private StrategyRunMapper strategyRunMapper;

    private StrategyPerformanceCalculator calculator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        calculator = new StrategyPerformanceCalculator(strategyRunJpaRepository, strategyRunMapper);
    }

    @Test
    void emptyRuns_returnsEmptyReport() {
        when(strategyRunJpaRepository.findByStrategyIdAndStatus("STR-1", StrategyRunStatus.COMPLETED))
                .thenReturn(List.of());
        when(strategyRunMapper.toDomainList(List.of())).thenReturn(List.of());

        StrategyPerformanceReport report = calculator.calculate("STR-1");

        assertThat(report.getStrategyId()).isEqualTo("STR-1");
        assertThat(report.getTotalRuns()).isEqualTo(0);
        assertThat(report.getWinRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void winRate_calculatedCorrectly() {
        List<StrategyRun> runs = List.of(
                createRun("r1", new BigDecimal("500"), 60),
                createRun("r2", new BigDecimal("-200"), 30),
                createRun("r3", new BigDecimal("300"), 45));
        mockRuns("STR-1", runs);

        StrategyPerformanceReport report = calculator.calculate("STR-1");

        // 2 winning out of 3 = 66.6667%
        assertThat(report.getTotalRuns()).isEqualTo(3);
        assertThat(report.getWinningRuns()).isEqualTo(2);
        assertThat(report.getLosingRuns()).isEqualTo(1);
        assertThat(report.getWinRate()).isEqualByComparingTo(new BigDecimal("66.6700"));
    }

    @Test
    void profitFactor_grossProfitDividedByGrossLoss() {
        List<StrategyRun> runs = List.of(
                createRun("r1", new BigDecimal("1000"), 60),
                createRun("r2", new BigDecimal("-400"), 30),
                createRun("r3", new BigDecimal("600"), 45));
        mockRuns("STR-1", runs);

        StrategyPerformanceReport report = calculator.calculate("STR-1");

        // Gross profit = 1000 + 600 = 1600, gross loss = 400
        // PF = 1600 / 400 = 4.0
        assertThat(report.getGrossProfit()).isEqualByComparingTo(new BigDecimal("1600"));
        assertThat(report.getGrossLoss()).isEqualByComparingTo(new BigDecimal("400"));
        assertThat(report.getProfitFactor()).isEqualByComparingTo(new BigDecimal("4.00"));
    }

    @Test
    void profitFactor_noLosses_returns999() {
        List<StrategyRun> runs =
                List.of(createRun("r1", new BigDecimal("500"), 60), createRun("r2", new BigDecimal("300"), 30));
        mockRuns("STR-1", runs);

        StrategyPerformanceReport report = calculator.calculate("STR-1");

        assertThat(report.getProfitFactor()).isEqualByComparingTo(new BigDecimal("999.99"));
    }

    @Test
    void maxDrawdown_calculatedFromCumulativePnL() {
        // Runs in time order: +500, -800, +200, -100
        // Cumulative: 500, -300, -100, -200
        // Peak: 500, then drawdown from 500 to -300 = 800
        List<StrategyRun> runs = List.of(
                createRunWithTime("r1", new BigDecimal("500"), LocalDateTime.of(2025, 1, 1, 9, 30)),
                createRunWithTime("r2", new BigDecimal("-800"), LocalDateTime.of(2025, 1, 2, 9, 30)),
                createRunWithTime("r3", new BigDecimal("200"), LocalDateTime.of(2025, 1, 3, 9, 30)),
                createRunWithTime("r4", new BigDecimal("-100"), LocalDateTime.of(2025, 1, 4, 9, 30)));
        mockRuns("STR-1", runs);

        StrategyPerformanceReport report = calculator.calculate("STR-1");

        assertThat(report.getMaxDrawdown()).isEqualByComparingTo(new BigDecimal("800"));
    }

    @Test
    void sharpeRatio_calculatedCorrectly() {
        // Runs: +100, +100 (identical returns -> stdDev = 0 -> Sharpe = 0)
        List<StrategyRun> runs =
                List.of(createRun("r1", new BigDecimal("100"), 60), createRun("r2", new BigDecimal("100"), 60));
        mockRuns("STR-1", runs);

        StrategyPerformanceReport report = calculator.calculate("STR-1");

        // Identical returns -> std dev = 0 -> Sharpe = 0
        assertThat(report.getSharpeRatio()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void sharpeRatio_positiveForProfitableVariedReturns() {
        // Runs: +200, -50, +150, +100 -> mean = 100, variance > 0
        List<StrategyRun> runs = List.of(
                createRun("r1", new BigDecimal("200"), 60),
                createRun("r2", new BigDecimal("-50"), 30),
                createRun("r3", new BigDecimal("150"), 45),
                createRun("r4", new BigDecimal("100"), 60));
        mockRuns("STR-1", runs);

        StrategyPerformanceReport report = calculator.calculate("STR-1");

        assertThat(report.getSharpeRatio()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void avgPnLPerRun_calculatedCorrectly() {
        List<StrategyRun> runs =
                List.of(createRun("r1", new BigDecimal("600"), 60), createRun("r2", new BigDecimal("-200"), 30));
        mockRuns("STR-1", runs);

        StrategyPerformanceReport report = calculator.calculate("STR-1");

        // Total = 400, avg = 200
        assertThat(report.getTotalNetPnL()).isEqualByComparingTo(new BigDecimal("400"));
        assertThat(report.getAvgPnLPerRun()).isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    void maxFavorableAndAdverseExcursion_correctValues() {
        List<StrategyRun> runs = List.of(
                createRun("r1", new BigDecimal("500"), 60),
                createRun("r2", new BigDecimal("-300"), 30),
                createRun("r3", new BigDecimal("200"), 45));
        mockRuns("STR-1", runs);

        StrategyPerformanceReport report = calculator.calculate("STR-1");

        assertThat(report.getMaxFavorableExcursion()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(report.getMaxAdverseExcursion()).isEqualByComparingTo(new BigDecimal("-300"));
    }

    @Test
    void totalCharges_accumulatedAcrossRuns() {
        StrategyRun r1 = StrategyRun.builder()
                .id("r1")
                .strategyId("STR-1")
                .netPnl(new BigDecimal("500"))
                .totalCharges(new BigDecimal("50"))
                .entryTime(LocalDateTime.now().minusHours(2))
                .exitTime(LocalDateTime.now().minusHours(1))
                .build();
        StrategyRun r2 = StrategyRun.builder()
                .id("r2")
                .strategyId("STR-1")
                .netPnl(new BigDecimal("300"))
                .totalCharges(new BigDecimal("30"))
                .entryTime(LocalDateTime.now().minusHours(4))
                .exitTime(LocalDateTime.now().minusHours(3))
                .build();
        mockRuns("STR-1", List.of(r1, r2));

        StrategyPerformanceReport report = calculator.calculate("STR-1");

        assertThat(report.getTotalCharges()).isEqualByComparingTo(new BigDecimal("80"));
    }

    @Test
    void avgHoldingTimeMinutes_calculatedCorrectly() {
        // Run 1: 60 minutes, Run 2: 120 minutes -> avg = 90
        StrategyRun r1 = StrategyRun.builder()
                .id("r1")
                .strategyId("STR-1")
                .netPnl(new BigDecimal("100"))
                .entryTime(LocalDateTime.of(2025, 1, 1, 9, 15))
                .exitTime(LocalDateTime.of(2025, 1, 1, 10, 15))
                .build();
        StrategyRun r2 = StrategyRun.builder()
                .id("r2")
                .strategyId("STR-1")
                .netPnl(new BigDecimal("200"))
                .entryTime(LocalDateTime.of(2025, 1, 2, 9, 15))
                .exitTime(LocalDateTime.of(2025, 1, 2, 11, 15))
                .build();
        mockRuns("STR-1", List.of(r1, r2));

        StrategyPerformanceReport report = calculator.calculate("STR-1");

        assertThat(report.getAvgHoldingTimeMinutes()).isEqualTo(90);
    }

    // ---- Helpers ----

    private StrategyRun createRun(String id, BigDecimal netPnl, int holdingMinutes) {
        return StrategyRun.builder()
                .id(id)
                .strategyId("STR-1")
                .netPnl(netPnl)
                .entryTime(LocalDateTime.now().minusMinutes(holdingMinutes))
                .exitTime(LocalDateTime.now())
                .build();
    }

    private StrategyRun createRunWithTime(String id, BigDecimal netPnl, LocalDateTime entryTime) {
        return StrategyRun.builder()
                .id(id)
                .strategyId("STR-1")
                .netPnl(netPnl)
                .entryTime(entryTime)
                .exitTime(entryTime.plusHours(1))
                .build();
    }

    @SuppressWarnings("unchecked")
    private void mockRuns(String strategyId, List<StrategyRun> runs) {
        when(strategyRunJpaRepository.findByStrategyIdAndStatus(strategyId, StrategyRunStatus.COMPLETED))
                .thenReturn(List.of()); // entities don't matter, we mock the mapper
        when(strategyRunMapper.toDomainList(List.of())).thenReturn(runs);
    }
}
