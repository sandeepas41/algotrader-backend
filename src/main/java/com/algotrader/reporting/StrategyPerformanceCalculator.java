package com.algotrader.reporting;

import com.algotrader.domain.enums.StrategyRunStatus;
import com.algotrader.domain.model.StrategyRun;
import com.algotrader.entity.StrategyRunEntity;
import com.algotrader.mapper.StrategyRunMapper;
import com.algotrader.repository.jpa.StrategyRunJpaRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Computes strategy-level performance metrics across all closed runs.
 *
 * <p>Calculates: win rate, average P&L per run, profit factor, max drawdown,
 * Sharpe ratio, average holding time, max favorable/adverse excursion,
 * total charges, and average adjustments per run.
 *
 * <p>Only COMPLETED runs are included in the calculations. ACTIVE and ABORTED
 * runs are excluded to avoid skewing metrics with incomplete data.
 */
@Service
public class StrategyPerformanceCalculator {

    private static final Logger log = LoggerFactory.getLogger(StrategyPerformanceCalculator.class);

    private final StrategyRunJpaRepository strategyRunJpaRepository;
    private final StrategyRunMapper strategyRunMapper;

    public StrategyPerformanceCalculator(
            StrategyRunJpaRepository strategyRunJpaRepository, StrategyRunMapper strategyRunMapper) {
        this.strategyRunJpaRepository = strategyRunJpaRepository;
        this.strategyRunMapper = strategyRunMapper;
    }

    /**
     * Calculates performance metrics for the given strategy across all its completed runs.
     *
     * @param strategyId the strategy to calculate performance for
     * @return performance report, or empty report if no completed runs exist
     */
    public StrategyPerformanceReport calculate(String strategyId) {
        List<StrategyRunEntity> entities =
                strategyRunJpaRepository.findByStrategyIdAndStatus(strategyId, StrategyRunStatus.COMPLETED);
        List<StrategyRun> runs = strategyRunMapper.toDomainList(entities);

        if (runs.isEmpty()) {
            return StrategyPerformanceReport.empty(strategyId);
        }

        int totalRuns = runs.size();
        long winningRuns = runs.stream()
                .filter(r -> r.getNetPnl() != null && r.getNetPnl().compareTo(BigDecimal.ZERO) > 0)
                .count();
        int losingRuns = totalRuns - (int) winningRuns;

        // Win rate as percentage
        BigDecimal winRate = BigDecimal.valueOf(winningRuns)
                .divide(BigDecimal.valueOf(totalRuns), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        // Total net P&L
        BigDecimal totalNetPnL = runs.stream()
                .map(StrategyRun::getNetPnl)
                .filter(pnl -> pnl != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgPnLPerRun = totalNetPnL.divide(BigDecimal.valueOf(totalRuns), 2, RoundingMode.HALF_UP);

        // Gross profit (sum of positive P&Ls) and gross loss (sum of absolute negative P&Ls)
        BigDecimal grossProfit = runs.stream()
                .map(StrategyRun::getNetPnl)
                .filter(pnl -> pnl != null && pnl.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossLoss = runs.stream()
                .map(StrategyRun::getNetPnl)
                .filter(pnl -> pnl != null && pnl.compareTo(BigDecimal.ZERO) < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Profit factor = gross profit / gross loss
        BigDecimal profitFactor;
        if (grossLoss.compareTo(BigDecimal.ZERO) > 0) {
            profitFactor = grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP);
        } else {
            profitFactor = grossProfit.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(999.99) : BigDecimal.ZERO;
        }

        BigDecimal maxDrawdown = calculateMaxDrawdown(runs);
        BigDecimal sharpeRatio = calculateSharpeRatio(runs);

        // Average holding time
        Duration avgHoldingTime = Duration.ofMinutes((long) runs.stream()
                .mapToLong(r -> getHoldingDuration(r).toMinutes())
                .average()
                .orElse(0));

        // Best and worst single runs
        BigDecimal maxFavorableExcursion = runs.stream()
                .map(StrategyRun::getNetPnl)
                .filter(pnl -> pnl != null)
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);

        BigDecimal maxAdverseExcursion = runs.stream()
                .map(StrategyRun::getNetPnl)
                .filter(pnl -> pnl != null)
                .min(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);

        // Total charges
        BigDecimal totalCharges = runs.stream()
                .map(StrategyRun::getTotalCharges)
                .filter(c -> c != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Average adjustments per run
        double avgAdjustments = runs.stream()
                .mapToInt(StrategyRun::getAdjustmentCount)
                .average()
                .orElse(0);

        log.debug(
                "Performance calculated for strategy {}: {} runs, {}% win rate, PF={}, maxDD={}",
                strategyId, totalRuns, winRate, profitFactor, maxDrawdown);

        return StrategyPerformanceReport.builder()
                .strategyId(strategyId)
                .totalRuns(totalRuns)
                .winningRuns((int) winningRuns)
                .losingRuns(losingRuns)
                .winRate(winRate)
                .totalNetPnL(totalNetPnL)
                .avgPnLPerRun(avgPnLPerRun)
                .grossProfit(grossProfit)
                .grossLoss(grossLoss)
                .profitFactor(profitFactor)
                .maxDrawdown(maxDrawdown)
                .sharpeRatio(sharpeRatio)
                .avgHoldingTimeMinutes(avgHoldingTime.toMinutes())
                .maxFavorableExcursion(maxFavorableExcursion)
                .maxAdverseExcursion(maxAdverseExcursion)
                .totalCharges(totalCharges)
                .avgAdjustmentsPerRun(BigDecimal.valueOf(avgAdjustments).setScale(1, RoundingMode.HALF_UP))
                .build();
    }

    /**
     * Calculates max drawdown from the cumulative P&L sequence of runs sorted by entry time.
     * Drawdown = peak cumulative P&L - current cumulative P&L.
     */
    BigDecimal calculateMaxDrawdown(List<StrategyRun> runs) {
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        BigDecimal cumulativePnL = BigDecimal.ZERO;

        List<StrategyRun> sorted = runs.stream()
                .sorted(Comparator.comparing(StrategyRun::getEntryTime))
                .toList();

        for (StrategyRun run : sorted) {
            if (run.getNetPnl() != null) {
                cumulativePnL = cumulativePnL.add(run.getNetPnl());
                if (cumulativePnL.compareTo(peak) > 0) {
                    peak = cumulativePnL;
                }
                BigDecimal drawdown = peak.subtract(cumulativePnL);
                if (drawdown.compareTo(maxDrawdown) > 0) {
                    maxDrawdown = drawdown;
                }
            }
        }

        return maxDrawdown;
    }

    /**
     * Simplified Sharpe ratio: mean return / standard deviation of returns.
     * Not annualized since runs are not time-uniform.
     */
    BigDecimal calculateSharpeRatio(List<StrategyRun> runs) {
        if (runs.size() < 2) {
            return BigDecimal.ZERO;
        }

        List<BigDecimal> returns = runs.stream()
                .map(r -> r.getNetPnl() != null ? r.getNetPnl() : BigDecimal.ZERO)
                .toList();

        double mean =
                returns.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);

        double variance = returns.stream()
                .mapToDouble(r -> Math.pow(r.doubleValue() - mean, 2))
                .average()
                .orElse(0);

        double stdDev = Math.sqrt(variance);

        if (stdDev == 0) {
            return BigDecimal.ZERO;
        }

        return BigDecimal.valueOf(mean / stdDev).setScale(2, RoundingMode.HALF_UP);
    }

    private Duration getHoldingDuration(StrategyRun run) {
        if (run.getExitTime() == null) {
            return Duration.between(run.getEntryTime(), LocalDateTime.now());
        }
        return Duration.between(run.getEntryTime(), run.getExitTime());
    }
}
