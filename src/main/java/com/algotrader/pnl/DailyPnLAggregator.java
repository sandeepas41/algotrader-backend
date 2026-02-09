package com.algotrader.pnl;

import com.algotrader.config.RedisConfig;
import com.algotrader.domain.enums.MarketPhase;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.model.Trade;
import com.algotrader.entity.DailyPnlEntity;
import com.algotrader.entity.TradeEntity;
import com.algotrader.event.MarketStatusEvent;
import com.algotrader.mapper.TradeMapper;
import com.algotrader.repository.jpa.DailyPnlJpaRepository;
import com.algotrader.repository.jpa.TradeJpaRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * End-of-day P&L aggregation service that computes daily P&L with full charge breakdowns.
 *
 * <p>This service performs two functions:
 * <ul>
 *   <li><b>Real-time Redis persistence:</b> On every realized P&L change, persists the running
 *       daily total to Redis ({@code algo:daily:pnl:{date}}) for crash recovery. If the JVM
 *       crashes mid-day, the running total can be recovered from Redis on restart.</li>
 *   <li><b>End-of-day aggregation:</b> Triggered by {@link MarketStatusEvent} when market
 *       transitions to CLOSED. Fetches all trades for the day from H2, computes gross/net P&L
 *       with charges, trade statistics, and persists to the daily_pnl table for historical
 *       reporting.</li>
 * </ul>
 *
 * <p>The daily_pnl table has one row per trading day and is used by the dashboard for
 * P&L charts and performance metrics.
 */
@Service
public class DailyPnLAggregator {

    private static final Logger log = LoggerFactory.getLogger(DailyPnLAggregator.class);

    private final TradeJpaRepository tradeJpaRepository;
    private final TradeMapper tradeMapper;
    private final ChargeCalculator chargeCalculator;
    private final DailyPnlJpaRepository dailyPnlJpaRepository;
    private final PnLCalculationService pnLCalculationService;
    private final RedisTemplate<String, Object> redisTemplate;

    public DailyPnLAggregator(
            TradeJpaRepository tradeJpaRepository,
            TradeMapper tradeMapper,
            ChargeCalculator chargeCalculator,
            DailyPnlJpaRepository dailyPnlJpaRepository,
            PnLCalculationService pnLCalculationService,
            RedisTemplate<String, Object> redisTemplate) {
        this.tradeJpaRepository = tradeJpaRepository;
        this.tradeMapper = tradeMapper;
        this.chargeCalculator = chargeCalculator;
        this.dailyPnlJpaRepository = dailyPnlJpaRepository;
        this.pnLCalculationService = pnLCalculationService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Triggered on market close (POST_CLOSE -> CLOSED transition).
     * Aggregates all trades from today into a single DailyPnlEntity and persists to H2.
     */
    @EventListener
    public void onMarketClose(MarketStatusEvent event) {
        if (event.getCurrentPhase() == MarketPhase.CLOSED && event.getPreviousPhase() == MarketPhase.POST_CLOSE) {
            aggregateEndOfDay();
        }
    }

    /**
     * Aggregates today's trades into a daily P&L summary and persists to H2.
     *
     * <p>Computes gross P&L (sell turnover - buy turnover), total charges, net P&L,
     * trade counts (total/winning/losing), and max drawdown from the running total.
     */
    public void aggregateEndOfDay() {
        LocalDate today = LocalDate.now();
        log.info("Starting end-of-day P&L aggregation for {}", today);

        // Fetch all trades from today
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.plusDays(1).atStartOfDay();
        List<TradeEntity> tradeEntities = tradeJpaRepository.findByDateRange(startOfDay, endOfDay);
        List<Trade> trades = tradeMapper.toDomainList(tradeEntities);

        if (trades.isEmpty()) {
            log.info("No trades today ({}), skipping aggregation", today);
            return;
        }

        // Calculate buy/sell turnovers and quantities
        BigDecimal totalBuyTurnover = BigDecimal.ZERO;
        BigDecimal totalSellTurnover = BigDecimal.ZERO;
        int totalBuyQty = 0;
        int totalSellQty = 0;
        int winningTrades = 0;
        int losingTrades = 0;

        for (Trade trade : trades) {
            BigDecimal tradeValue = trade.getPrice().multiply(BigDecimal.valueOf(trade.getQuantity()));
            if (trade.getSide() == OrderSide.BUY) {
                totalBuyTurnover = totalBuyTurnover.add(tradeValue);
                totalBuyQty += trade.getQuantity();
            } else {
                totalSellTurnover = totalSellTurnover.add(tradeValue);
                totalSellQty += trade.getQuantity();
            }

            // Count winning/losing based on per-trade P&L
            if (trade.getPnl() != null) {
                if (trade.getPnl().compareTo(BigDecimal.ZERO) > 0) {
                    winningTrades++;
                } else if (trade.getPnl().compareTo(BigDecimal.ZERO) < 0) {
                    losingTrades++;
                }
            }
        }

        // Gross P&L: sell turnover - buy turnover
        BigDecimal grossPnl = totalSellTurnover.subtract(totalBuyTurnover).setScale(2, RoundingMode.HALF_UP);

        // Aggregate charges from trades (already computed per-trade by ChargeCalculator)
        BigDecimal totalCharges = trades.stream()
                .filter(t -> t.getCharges() != null)
                .map(t -> t.getCharges().getTotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        // Net P&L: realized P&L from trades minus total charges
        BigDecimal realizedPnl = trades.stream()
                .filter(t -> t.getPnl() != null)
                .map(Trade::getPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        // Unrealized from current open positions
        BigDecimal unrealizedPnl = pnLCalculationService.calculateTotalUnrealizedPnl();

        // Max drawdown: compute from running P&L of trades sorted by time
        BigDecimal maxDrawdown = calculateMaxDrawdown(trades);

        // Persist or update daily record
        DailyPnlEntity entity = dailyPnlJpaRepository
                .findByDate(today)
                .orElse(DailyPnlEntity.builder()
                        .date(today)
                        .createdAt(LocalDateTime.now())
                        .build());

        entity.setRealizedPnl(realizedPnl);
        entity.setUnrealizedPnl(unrealizedPnl);
        entity.setTotalTrades(trades.size());
        entity.setWinningTrades(winningTrades);
        entity.setLosingTrades(losingTrades);
        entity.setMaxDrawdown(maxDrawdown);

        dailyPnlJpaRepository.save(entity);

        log.info(
                "Daily P&L aggregated: date={}, realized={}, unrealized={}, trades={}, charges={}, maxDD={}",
                today,
                realizedPnl,
                unrealizedPnl,
                trades.size(),
                totalCharges,
                maxDrawdown);
    }

    /**
     * Persists the current daily realized P&L to Redis for crash recovery.
     *
     * <p>Called whenever a trade is completed and realized P&L changes. The key
     * {@code algo:daily:pnl:{date}} stores the running total with a 36-hour TTL
     * (covers overnight + next trading day).
     *
     * @param dailyRealizedPnl the current running total of realized P&L for today
     */
    public void persistDailyPnlToRedis(BigDecimal dailyRealizedPnl) {
        String key = RedisConfig.KEY_PREFIX_DAILY_PNL + LocalDate.now();
        // 36-hour TTL covers overnight and next trading session
        redisTemplate.opsForValue().set(key, dailyRealizedPnl.toPlainString(), Duration.ofHours(36));
        log.debug("Daily P&L persisted to Redis: {} = {}", key, dailyRealizedPnl);
    }

    /**
     * Recovers the daily realized P&L from Redis (for crash recovery on restart).
     *
     * @return the recovered P&L, or ZERO if not found
     */
    public BigDecimal recoverDailyPnlFromRedis() {
        String key = RedisConfig.KEY_PREFIX_DAILY_PNL + LocalDate.now();
        Object value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            BigDecimal recovered = new BigDecimal(value.toString());
            log.info("Recovered daily P&L from Redis: {}", recovered);
            return recovered;
        }
        return BigDecimal.ZERO;
    }

    /**
     * Calculates max drawdown from a list of trades sorted by execution time.
     *
     * <p>Drawdown = peak running P&L - current running P&L. Max drawdown is the
     * largest drawdown observed during the day.
     */
    BigDecimal calculateMaxDrawdown(List<Trade> trades) {
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal runningPnl = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (Trade trade : trades) {
            if (trade.getPnl() != null) {
                runningPnl = runningPnl.add(trade.getPnl());
            }
            if (runningPnl.compareTo(peak) > 0) {
                peak = runningPnl;
            }
            BigDecimal drawdown = peak.subtract(runningPnl);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }

        return maxDrawdown.setScale(2, RoundingMode.HALF_UP);
    }
}
