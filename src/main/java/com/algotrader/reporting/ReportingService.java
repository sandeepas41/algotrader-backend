package com.algotrader.reporting;

import com.algotrader.domain.enums.GroupBy;
import com.algotrader.entity.DailyPnlEntity;
import com.algotrader.entity.TradeEntity;
import com.algotrader.repository.jpa.DailyPnlJpaRepository;
import com.algotrader.repository.jpa.TradeJpaRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Central reporting service orchestrating P&L reports, charge reports, strategy performance,
 * and trade summaries.
 *
 * <p>P&L reports use DailyPnlEntity rows (one per trading day, populated by DailyPnLAggregator)
 * and group them by day, week, or month. Charge reports aggregate itemized charges from trades.
 */
@Service
public class ReportingService {

    private static final Logger log = LoggerFactory.getLogger(ReportingService.class);

    private final DailyPnlJpaRepository dailyPnlJpaRepository;
    private final TradeJpaRepository tradeJpaRepository;
    private final StrategyPerformanceCalculator strategyPerformanceCalculator;

    public ReportingService(
            DailyPnlJpaRepository dailyPnlJpaRepository,
            TradeJpaRepository tradeJpaRepository,
            StrategyPerformanceCalculator strategyPerformanceCalculator) {
        this.dailyPnlJpaRepository = dailyPnlJpaRepository;
        this.tradeJpaRepository = tradeJpaRepository;
        this.strategyPerformanceCalculator = strategyPerformanceCalculator;
    }

    /**
     * Generates a P&L report grouped by day, week, or month for the given date range.
     */
    public PnLReport getPnLReport(LocalDate from, LocalDate to, GroupBy groupBy) {
        List<DailyPnlEntity> records = dailyPnlJpaRepository.findByDateRange(from, to);

        Map<String, PnLReportEntry.PnLReportEntryBuilder> grouped = new TreeMap<>();

        for (DailyPnlEntity record : records) {
            String key =
                    switch (groupBy) {
                        case DAILY -> record.getDate().toString();
                        case WEEKLY -> getWeekKey(record.getDate());
                        case MONTHLY ->
                            record.getDate().getYear() + "-"
                                    + String.format("%02d", record.getDate().getMonthValue());
                    };

            PnLReportEntry.PnLReportEntryBuilder builder = grouped.computeIfAbsent(key, k -> PnLReportEntry.builder()
                    .period(k)
                    .realizedPnl(BigDecimal.ZERO)
                    .unrealizedPnl(BigDecimal.ZERO)
                    .tradeCount(0)
                    .winningTrades(0)
                    .losingTrades(0)
                    .maxDrawdown(BigDecimal.ZERO));

            // Aggregate into the builder (we need to build intermediate to read fields)
            PnLReportEntry current = builder.build();
            builder.realizedPnl(current.getRealizedPnl()
                    .add(record.getRealizedPnl() != null ? record.getRealizedPnl() : BigDecimal.ZERO));
            builder.unrealizedPnl(current.getUnrealizedPnl()
                    .add(record.getUnrealizedPnl() != null ? record.getUnrealizedPnl() : BigDecimal.ZERO));
            builder.tradeCount(current.getTradeCount() + record.getTotalTrades());
            builder.winningTrades(current.getWinningTrades() + record.getWinningTrades());
            builder.losingTrades(current.getLosingTrades() + record.getLosingTrades());
            // Take the worst drawdown across grouped days
            BigDecimal dayDrawdown = record.getMaxDrawdown() != null ? record.getMaxDrawdown() : BigDecimal.ZERO;
            if (dayDrawdown.compareTo(current.getMaxDrawdown()) > 0) {
                builder.maxDrawdown(dayDrawdown);
            }
        }

        List<PnLReportEntry> entries = grouped.values().stream()
                .map(PnLReportEntry.PnLReportEntryBuilder::build)
                .toList();

        BigDecimal totalRealized =
                entries.stream().map(PnLReportEntry::getRealizedPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalUnrealized =
                entries.stream().map(PnLReportEntry::getUnrealizedPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalTrades =
                entries.stream().mapToInt(PnLReportEntry::getTradeCount).sum();

        return PnLReport.builder()
                .from(from)
                .to(to)
                .groupBy(groupBy)
                .entries(new ArrayList<>(entries))
                .totalRealizedPnl(totalRealized)
                .totalUnrealizedPnl(totalUnrealized)
                .tradingDays(records.size())
                .totalTrades(totalTrades)
                .build();
    }

    /**
     * Returns strategy performance metrics for the given strategy.
     */
    public StrategyPerformanceReport getStrategyPerformance(String strategyId) {
        return strategyPerformanceCalculator.calculate(strategyId);
    }

    /**
     * Generates an itemized charge breakdown report over a date range.
     * Reads from trades directly for accurate per-charge-type aggregation.
     */
    public ChargeReport getChargeReport(LocalDate from, LocalDate to) {
        List<TradeEntity> trades = tradeJpaRepository.findByDateRange(
                from.atStartOfDay(), to.plusDays(1).atStartOfDay());

        BigDecimal totalBrokerage = BigDecimal.ZERO;
        BigDecimal totalSTT = BigDecimal.ZERO;
        BigDecimal totalExchange = BigDecimal.ZERO;
        BigDecimal totalSebi = BigDecimal.ZERO;
        BigDecimal totalStamp = BigDecimal.ZERO;
        BigDecimal totalGST = BigDecimal.ZERO;
        int tradeCount = trades.size();

        for (TradeEntity trade : trades) {
            if (trade.getBrokerage() != null) {
                totalBrokerage = totalBrokerage.add(trade.getBrokerage());
            }
            if (trade.getStt() != null) {
                totalSTT = totalSTT.add(trade.getStt());
            }
            if (trade.getExchangeCharges() != null) {
                totalExchange = totalExchange.add(trade.getExchangeCharges());
            }
            if (trade.getSebiCharges() != null) {
                totalSebi = totalSebi.add(trade.getSebiCharges());
            }
            if (trade.getStampDuty() != null) {
                totalStamp = totalStamp.add(trade.getStampDuty());
            }
            if (trade.getGst() != null) {
                totalGST = totalGST.add(trade.getGst());
            }
        }

        BigDecimal grandTotal = totalBrokerage
                .add(totalSTT)
                .add(totalExchange)
                .add(totalSebi)
                .add(totalStamp)
                .add(totalGST);

        // Count distinct trading days from the trades
        long tradingDays = trades.stream()
                .filter(t -> t.getExecutedAt() != null)
                .map(t -> t.getExecutedAt().toLocalDate())
                .distinct()
                .count();

        return ChargeReport.builder()
                .from(from)
                .to(to)
                .totalBrokerage(totalBrokerage)
                .totalSTT(totalSTT)
                .totalExchangeCharges(totalExchange)
                .totalSebiCharges(totalSebi)
                .totalStampDuty(totalStamp)
                .totalGST(totalGST)
                .grandTotal(grandTotal)
                .tradingDays((int) tradingDays)
                .totalTrades(tradeCount)
                .build();
    }

    /**
     * Returns the Monday of the week containing the given date (ISO week).
     */
    private String getWeekKey(LocalDate date) {
        LocalDate weekStart = date.minusDays(date.getDayOfWeek().getValue() - 1);
        return weekStart.toString();
    }
}
