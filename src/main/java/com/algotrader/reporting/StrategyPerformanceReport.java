package com.algotrader.reporting;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * Aggregated performance metrics for a strategy computed across all its closed runs.
 *
 * <p>Key metrics:
 * <ul>
 *   <li>Win rate and profit factor for profitability assessment</li>
 *   <li>Max drawdown and Sharpe ratio for risk assessment</li>
 *   <li>Average holding time and adjustments for operational insight</li>
 *   <li>Max favorable/adverse excursion for best/worst single run</li>
 * </ul>
 */
@Data
@Builder
public class StrategyPerformanceReport {

    private String strategyId;
    private int totalRuns;
    private int winningRuns;
    private int losingRuns;
    private BigDecimal winRate;
    private BigDecimal totalNetPnL;
    private BigDecimal avgPnLPerRun;
    private BigDecimal grossProfit;
    private BigDecimal grossLoss;
    private BigDecimal profitFactor;
    private BigDecimal maxDrawdown;
    private BigDecimal sharpeRatio;
    private long avgHoldingTimeMinutes;
    private BigDecimal maxFavorableExcursion;
    private BigDecimal maxAdverseExcursion;
    private BigDecimal totalCharges;
    private BigDecimal avgAdjustmentsPerRun;

    public static StrategyPerformanceReport empty(String strategyId) {
        return StrategyPerformanceReport.builder()
                .strategyId(strategyId)
                .totalRuns(0)
                .winRate(BigDecimal.ZERO)
                .totalNetPnL(BigDecimal.ZERO)
                .avgPnLPerRun(BigDecimal.ZERO)
                .grossProfit(BigDecimal.ZERO)
                .grossLoss(BigDecimal.ZERO)
                .profitFactor(BigDecimal.ZERO)
                .maxDrawdown(BigDecimal.ZERO)
                .sharpeRatio(BigDecimal.ZERO)
                .maxFavorableExcursion(BigDecimal.ZERO)
                .maxAdverseExcursion(BigDecimal.ZERO)
                .totalCharges(BigDecimal.ZERO)
                .avgAdjustmentsPerRun(BigDecimal.ZERO)
                .build();
    }
}
