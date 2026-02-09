package com.algotrader.pnl;

import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.Trade;
import com.algotrader.domain.vo.ChargeBreakdown;
import com.algotrader.entity.TradeEntity;
import com.algotrader.mapper.TradeMapper;
import com.algotrader.repository.jpa.TradeJpaRepository;
import com.algotrader.repository.redis.PositionRedisRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Computes realized and unrealized P&L at position, strategy, and account levels.
 *
 * <p>This is the central P&L engine for the trading platform. It provides:
 * <ul>
 *   <li><b>Unrealized P&L:</b> (lastPrice - averagePrice) * quantity for open positions.
 *       Quantity is signed (positive=long, negative=short), so the subtraction direction
 *       handles both longs and shorts correctly.</li>
 *   <li><b>Realized P&L:</b> Aggregated from closed trades in H2 via TradeJpaRepository.</li>
 *   <li><b>Strategy-level:</b> Sums unrealized across strategy positions + realized from trades.</li>
 *   <li><b>Account-level daily:</b> Total realized (today's trades) + unrealized (all positions).</li>
 * </ul>
 *
 * <p>Position data is read from Redis (real-time), while trade history is in H2.
 * Charges are calculated by {@link ChargeCalculator} and deducted for net P&L.
 *
 * <p><b>Thread safety:</b> All methods are read-only queries, so no synchronization needed.
 */
@Service
public class PnLCalculationService {

    private static final Logger log = LoggerFactory.getLogger(PnLCalculationService.class);

    private final PositionRedisRepository positionRedisRepository;
    private final TradeJpaRepository tradeJpaRepository;
    private final TradeMapper tradeMapper;
    private final ChargeCalculator chargeCalculator;

    public PnLCalculationService(
            PositionRedisRepository positionRedisRepository,
            TradeJpaRepository tradeJpaRepository,
            TradeMapper tradeMapper,
            ChargeCalculator chargeCalculator) {
        this.positionRedisRepository = positionRedisRepository;
        this.tradeJpaRepository = tradeJpaRepository;
        this.tradeMapper = tradeMapper;
        this.chargeCalculator = chargeCalculator;
    }

    /**
     * Calculates unrealized P&L for a single position.
     *
     * <p>Formula: (lastPrice - averagePrice) * quantity.
     * Since quantity is signed (positive=long, negative=short), the formula
     * naturally handles both directions:
     * <ul>
     *   <li>Long: bought at 100, last=110, qty=+5 -> (110-100)*5 = +50</li>
     *   <li>Short: sold at 200, last=180, qty=-5 -> (180-200)*(-5) = +100</li>
     * </ul>
     *
     * @param position the position to calculate P&L for
     * @return unrealized P&L, or ZERO if prices are missing
     */
    public BigDecimal calculateUnrealizedPnl(Position position) {
        if (position.getLastPrice() == null || position.getAveragePrice() == null) {
            return BigDecimal.ZERO;
        }

        return position.getLastPrice()
                .subtract(position.getAveragePrice())
                .multiply(BigDecimal.valueOf(position.getQuantity()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates total unrealized P&L for all positions belonging to a strategy.
     *
     * @param strategyId the strategy ID
     * @return total unrealized P&L across all strategy positions
     */
    public BigDecimal calculateStrategyUnrealizedPnl(String strategyId) {
        List<Position> positions = positionRedisRepository.findByStrategyId(strategyId);
        return positions.stream()
                .map(this::calculateUnrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates realized P&L from today's trades for a strategy.
     *
     * @param strategyId the strategy ID
     * @return realized P&L from today's closed trades
     */
    public BigDecimal calculateStrategyRealizedPnl(String strategyId) {
        List<TradeEntity> entities = tradeJpaRepository.findByStrategyId(strategyId);
        List<Trade> trades = tradeMapper.toDomainList(entities);

        return trades.stream()
                .filter(t -> t.getPnl() != null)
                .map(Trade::getPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates total charges for a strategy's trades.
     *
     * @param strategyId the strategy ID
     * @return aggregated charge breakdown, or zero if no trades
     */
    public ChargeBreakdown calculateStrategyCharges(String strategyId) {
        List<TradeEntity> entities = tradeJpaRepository.findByStrategyId(strategyId);
        List<Trade> trades = tradeMapper.toDomainList(entities);

        BigDecimal totalBrokerage = BigDecimal.ZERO;
        BigDecimal totalStt = BigDecimal.ZERO;
        BigDecimal totalExchange = BigDecimal.ZERO;
        BigDecimal totalSebi = BigDecimal.ZERO;
        BigDecimal totalStamp = BigDecimal.ZERO;
        BigDecimal totalGst = BigDecimal.ZERO;

        for (Trade trade : trades) {
            if (trade.getCharges() != null) {
                totalBrokerage = totalBrokerage.add(trade.getCharges().getBrokerage());
                totalStt = totalStt.add(trade.getCharges().getStt());
                totalExchange = totalExchange.add(trade.getCharges().getExchangeCharges());
                totalSebi = totalSebi.add(trade.getCharges().getSebiCharges());
                totalStamp = totalStamp.add(trade.getCharges().getStampDuty());
                totalGst = totalGst.add(trade.getCharges().getGst());
            }
        }

        return ChargeBreakdown.builder()
                .brokerage(totalBrokerage)
                .stt(totalStt)
                .exchangeCharges(totalExchange)
                .sebiCharges(totalSebi)
                .stampDuty(totalStamp)
                .gst(totalGst)
                .build();
    }

    /**
     * Calculates account-wide daily P&L (realized + unrealized).
     *
     * <p>Realized comes from H2 trades executed today. Unrealized comes from
     * all open positions in Redis (mark-to-market).
     *
     * @return total daily P&L
     */
    public BigDecimal calculateDailyPnl() {
        // Realized: sum of pnl from today's trades
        BigDecimal todayRealized =
                tradeJpaRepository.getDailyRealizedPnl(LocalDate.now().atStartOfDay());

        // Unrealized: sum across all open positions
        BigDecimal totalUnrealized = positionRedisRepository.findAll().stream()
                .filter(p -> p.getQuantity() != 0)
                .map(this::calculateUnrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return todayRealized.add(totalUnrealized).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Returns the total unrealized P&L across all open positions.
     *
     * @return total unrealized P&L
     */
    public BigDecimal calculateTotalUnrealizedPnl() {
        return positionRedisRepository.findAll().stream()
                .filter(p -> p.getQuantity() != 0)
                .map(this::calculateUnrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Returns today's total realized P&L from H2 trades.
     *
     * @return today's realized P&L
     */
    public BigDecimal calculateTodayRealizedPnl() {
        return tradeJpaRepository.getDailyRealizedPnl(LocalDate.now().atStartOfDay());
    }
}
