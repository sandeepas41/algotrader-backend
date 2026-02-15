package com.algotrader.simulator;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.Tick;
import com.algotrader.event.OrderEvent;
import com.algotrader.event.OrderEventType;
import com.algotrader.event.TickEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Tracks virtual positions during paper trading simulation.
 *
 * <p>Listens for FILLED order events from VirtualOrderBook and maintains an in-memory
 * position book with signed quantity (positive = long, negative = short). Updates
 * unrealized P&L on every replay tick.
 *
 * <p>Position netting: BUY adds to quantity, SELL subtracts. When a fill reverses the
 * sign (e.g., long 50 -> sell 100 -> short 50), the position is updated with a new
 * average price for the remaining quantity. When quantity reaches zero, realized P&L
 * is calculated and the position is closed.
 *
 * <p>Only responds to events from VirtualOrderBook to avoid interfering with live positions.
 */
@Service
public class VirtualPositionManager {

    private static final Logger log = LoggerFactory.getLogger(VirtualPositionManager.class);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Virtual positions indexed by tradingSymbol. */
    private final Map<String, Position> positions = new ConcurrentHashMap<>();

    /** Cumulative realized P&L across all closed positions. */
    private volatile BigDecimal totalRealizedPnl = BigDecimal.ZERO;

    /**
     * Updates position on a filled order. Only processes fills from VirtualOrderBook.
     */
    @EventListener
    @org.springframework.core.annotation.Order(16)
    public void onOrderFilled(OrderEvent event) {
        // Only process fills from VirtualOrderBook
        if (!(event.getSource() instanceof VirtualOrderBook)) {
            return;
        }

        if (event.getEventType() != OrderEventType.FILLED) {
            return;
        }

        updatePosition(event.getOrder());
    }

    /**
     * Updates unrealized P&L for all virtual positions on each replay tick.
     */
    @EventListener
    @org.springframework.core.annotation.Order(17)
    public void onTick(TickEvent event) {
        // Only process replay ticks
        if (!(event.getSource() instanceof TickPlayer)) {
            return;
        }

        Tick tick = event.getTick();
        positions.values().stream()
                .filter(p -> tick.getInstrumentToken().equals(p.getInstrumentToken()))
                .forEach(p -> {
                    p.setLastPrice(tick.getLastPrice());
                    p.setUnrealizedPnl(calculateUnrealizedPnl(p));
                    p.setLastUpdated(LocalDateTime.now(IST));
                });
    }

    /**
     * Returns all current virtual positions (both open and recently closed in this session).
     */
    public Map<String, List<Position>> getPositions() {
        Map<String, List<Position>> result = new HashMap<>();
        result.put("net", new ArrayList<>(positions.values()));
        result.put("day", new ArrayList<>(positions.values()));
        return result;
    }

    /**
     * Returns the total margin used across all open positions.
     * Uses a simplified 15% margin assumption for options.
     */
    public BigDecimal getTotalMarginUsed() {
        // 15% margin on notional value of all open positions
        return positions.values().stream()
                .filter(p -> p.getQuantity() != 0)
                .map(p -> {
                    BigDecimal price = p.getLastPrice() != null ? p.getLastPrice() : p.getAveragePrice();
                    return price.multiply(BigDecimal.valueOf(Math.abs(p.getQuantity())))
                            .multiply(new BigDecimal("0.15"));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Returns cumulative realized P&L across all closed positions.
     */
    public BigDecimal getTotalRealizedPnl() {
        return totalRealizedPnl;
    }

    /**
     * Resets all positions. Used when starting a new simulation session.
     */
    public void reset() {
        positions.clear();
        totalRealizedPnl = BigDecimal.ZERO;
    }

    /**
     * Returns the number of open positions (non-zero quantity).
     */
    public int getOpenPositionCount() {
        return (int)
                positions.values().stream().filter(p -> p.getQuantity() != 0).count();
    }

    // ---- Internal position update logic ----

    private void updatePosition(Order order) {
        String key = order.getTradingSymbol();
        if (key == null) {
            key = String.valueOf(order.getInstrumentToken());
        }

        Position position = positions.computeIfAbsent(key, k -> Position.builder()
                .id(UUID.randomUUID().toString())
                .instrumentToken(order.getInstrumentToken())
                .tradingSymbol(order.getTradingSymbol())
                .exchange(order.getExchange())
                .quantity(0)
                .averagePrice(BigDecimal.ZERO)
                .unrealizedPnl(BigDecimal.ZERO)
                .realizedPnl(BigDecimal.ZERO)
                .openedAt(LocalDateTime.now(IST))
                .build());

        int previousQuantity = position.getQuantity();
        int fillQuantity = order.getFilledQuantity();
        BigDecimal fillPrice = order.getAverageFillPrice();

        // Signed quantity change: BUY adds, SELL subtracts
        int quantityChange = order.getSide() == OrderSide.BUY ? fillQuantity : -fillQuantity;
        int newQuantity = previousQuantity + quantityChange;

        if (previousQuantity != 0 && Math.signum(newQuantity) != Math.signum(previousQuantity) || newQuantity == 0) {
            // Position is being closed (partially or fully) or reversed
            int closedQuantity = Math.min(Math.abs(previousQuantity), Math.abs(quantityChange));
            BigDecimal closingPnl = calculateClosingPnl(position, fillPrice, closedQuantity, previousQuantity);
            position.setRealizedPnl(position.getRealizedPnl().add(closingPnl));
            totalRealizedPnl = totalRealizedPnl.add(closingPnl);

            if (newQuantity != 0) {
                // Position reversed — new average price is the fill price for the remaining qty
                position.setAveragePrice(fillPrice);
            }
        } else if (Math.signum(newQuantity) == Math.signum(quantityChange)) {
            // Adding to position — recalculate VWAP
            BigDecimal existingNotional =
                    position.getAveragePrice().multiply(BigDecimal.valueOf(Math.abs(previousQuantity)));
            BigDecimal newNotional = fillPrice.multiply(BigDecimal.valueOf(Math.abs(quantityChange)));
            BigDecimal totalNotional = existingNotional.add(newNotional);

            if (Math.abs(newQuantity) > 0) {
                position.setAveragePrice(
                        totalNotional.divide(BigDecimal.valueOf(Math.abs(newQuantity)), 2, RoundingMode.HALF_UP));
            }
        }

        position.setQuantity(newQuantity);
        position.setLastUpdated(LocalDateTime.now(IST));

        if (newQuantity == 0) {
            position.setClosedAt(LocalDateTime.now(IST));
            log.debug("Virtual position closed: {} realized P&L={}", key, position.getRealizedPnl());
        } else {
            log.debug("Virtual position updated: {} qty={} avgPrice={}", key, newQuantity, position.getAveragePrice());
        }
    }

    /**
     * Calculates realized P&L for the closed portion of a position.
     * Long position P&L = (fillPrice - avgPrice) * closedQty
     * Short position P&L = (avgPrice - fillPrice) * closedQty
     */
    private BigDecimal calculateClosingPnl(
            Position position, BigDecimal fillPrice, int closedQuantity, int previousQuantity) {
        BigDecimal priceDiff;
        if (previousQuantity > 0) {
            // Was long: profit if fillPrice > avgPrice
            priceDiff = fillPrice.subtract(position.getAveragePrice());
        } else {
            // Was short: profit if fillPrice < avgPrice
            priceDiff = position.getAveragePrice().subtract(fillPrice);
        }
        return priceDiff.multiply(BigDecimal.valueOf(closedQuantity));
    }

    private BigDecimal calculateUnrealizedPnl(Position position) {
        if (position.getQuantity() == 0 || position.getLastPrice() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal priceDiff;
        if (position.getQuantity() > 0) {
            // Long: unrealized = (lastPrice - avgPrice) * qty
            priceDiff = position.getLastPrice().subtract(position.getAveragePrice());
        } else {
            // Short: unrealized = (avgPrice - lastPrice) * |qty|
            priceDiff = position.getAveragePrice().subtract(position.getLastPrice());
        }

        return priceDiff.multiply(BigDecimal.valueOf(Math.abs(position.getQuantity())));
    }
}
