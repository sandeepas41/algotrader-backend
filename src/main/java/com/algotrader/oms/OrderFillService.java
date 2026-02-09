package com.algotrader.oms;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.OrderFill;
import com.algotrader.entity.OrderFillEntity;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.event.OrderEvent;
import com.algotrader.mapper.OrderFillMapper;
import com.algotrader.repository.jpa.OrderFillJpaRepository;
import com.algotrader.repository.redis.OrderRedisRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Handles partial and full order fills from the broker.
 *
 * <p>Listens for PARTIALLY_FILLED and FILLED order events, creates fill records,
 * updates the order's filled quantity and VWAP (volume-weighted average price),
 * and persists both the fill record and updated order to Redis.
 *
 * <p>The fill processing pipeline:
 * <ol>
 *   <li>Create an {@link OrderFill} record for the incremental fill</li>
 *   <li>Persist the fill to H2 via {@link OrderFillJpaRepository}</li>
 *   <li>Recalculate VWAP across all fills for the order</li>
 *   <li>Update order status and filled quantities in Redis</li>
 *   <li>Publish position update events (via downstream listeners)</li>
 * </ol>
 *
 * <p>Post-fill actions:
 * <ul>
 *   <li>Caffeine margin cache is invalidated after any fill because fills
 *       change the available margin (#TODO Task 7.4 -- invalidate margin cache)</li>
 * </ul>
 */
@Service
public class OrderFillService {

    private static final Logger log = LoggerFactory.getLogger(OrderFillService.class);

    private final OrderFillJpaRepository orderFillJpaRepository;
    private final OrderFillMapper orderFillMapper;
    private final OrderRedisRepository orderRedisRepository;
    private final EventPublisherHelper eventPublisherHelper;

    public OrderFillService(
            OrderFillJpaRepository orderFillJpaRepository,
            OrderFillMapper orderFillMapper,
            OrderRedisRepository orderRedisRepository,
            EventPublisherHelper eventPublisherHelper) {
        this.orderFillJpaRepository = orderFillJpaRepository;
        this.orderFillMapper = orderFillMapper;
        this.orderRedisRepository = orderRedisRepository;
        this.eventPublisherHelper = eventPublisherHelper;
    }

    /**
     * Processes fill events from the broker (partial or full).
     *
     * <p>Creates a fill record, recalculates VWAP, and updates the order in Redis.
     * Each partial fill is stored individually so we can trace execution quality
     * (slippage analysis, fill distribution).
     */
    @EventListener(condition = "#event.eventType.name() == 'PARTIALLY_FILLED' || #event.eventType.name() == 'FILLED'")
    public void onOrderFill(OrderEvent event) {
        Order order = event.getOrder();
        OrderStatus previousStatus = event.getPreviousStatus();

        log.debug(
                "Processing fill: orderId={}, status={}, filledQty={}, avgPrice={}",
                order.getBrokerOrderId(),
                order.getStatus(),
                order.getFilledQuantity(),
                order.getAverageFillPrice());

        // Create fill record from the order's current fill data
        // The order snapshot from Kite already has cumulative filledQuantity and averageFillPrice
        OrderFill fill = createFillRecord(order);
        persistFill(fill);

        // Recalculate VWAP from all fills for this order
        recalculateVwap(order);

        // Update order in Redis
        order.setUpdatedAt(LocalDateTime.now());
        orderRedisRepository.save(order);

        // #TODO Task 7.4 -- Invalidate Caffeine margin cache after fill
        // cacheManager.getCache("margins").invalidate();

        log.info(
                "Fill processed: orderId={}, fillQty={}, fillPrice={}, totalFilled={}, vwap={}",
                order.getBrokerOrderId(),
                fill.getQuantity(),
                fill.getPrice(),
                order.getFilledQuantity(),
                order.getAverageFillPrice());
    }

    /**
     * Creates an OrderFill record from the order's current state.
     *
     * <p>For partial fills, the fill quantity is the difference between the current
     * filled quantity and the sum of previous fills. For full fills, it captures
     * the remaining unfilled quantity.
     */
    public OrderFill createFillRecord(Order order) {
        // Calculate incremental fill quantity by subtracting previous fills
        int previousFilledQty = getPreviousFilledQuantity(order.getBrokerOrderId());
        int incrementalQty = order.getFilledQuantity() - previousFilledQty;

        // If no incremental fill (duplicate event), use 0 but still create the record
        if (incrementalQty <= 0) {
            log.warn(
                    "Zero or negative incremental fill detected: orderId={}, totalFilled={}, previousFilled={}",
                    order.getBrokerOrderId(),
                    order.getFilledQuantity(),
                    previousFilledQty);
            incrementalQty = 0;
        }

        return OrderFill.builder()
                .id(UUID.randomUUID().toString())
                .orderId(order.getBrokerOrderId())
                .instrumentToken(order.getInstrumentToken())
                .tradingSymbol(order.getTradingSymbol())
                .quantity(incrementalQty)
                .price(order.getAverageFillPrice())
                .filledAt(LocalDateTime.now())
                .build();
    }

    /**
     * Calculates VWAP (Volume-Weighted Average Price) across all fills for an order.
     *
     * <p>VWAP = sum(price_i * qty_i) / sum(qty_i)
     *
     * <p>This is more accurate than Kite's reported average price because we track
     * each fill individually. Kite may round or truncate the average.
     */
    public BigDecimal calculateVwap(List<OrderFill> fills) {
        if (fills == null || fills.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalValue = BigDecimal.ZERO;
        int totalQuantity = 0;

        for (OrderFill fill : fills) {
            if (fill.getQuantity() > 0 && fill.getPrice() != null) {
                totalValue = totalValue.add(fill.getPrice().multiply(BigDecimal.valueOf(fill.getQuantity())));
                totalQuantity += fill.getQuantity();
            }
        }

        if (totalQuantity == 0) {
            return BigDecimal.ZERO;
        }

        return totalValue.divide(BigDecimal.valueOf(totalQuantity), 2, RoundingMode.HALF_UP);
    }

    /**
     * Determines the position event type based on the fill's effect on position size.
     *
     * @param currentQty   the position quantity after this fill (signed: +long, -short)
     * @param previousQty  the position quantity before this fill (signed)
     * @param fillSide     the side of the fill order
     */
    public String determinePositionImpact(int currentQty, int previousQty, OrderSide fillSide) {
        if (currentQty == 0) {
            return "CLOSED";
        }
        if (previousQty == 0) {
            return "OPENED";
        }
        if (Math.abs(currentQty) > Math.abs(previousQty)) {
            return "INCREASED";
        }
        return "REDUCED";
    }

    /**
     * Returns all fills for a given order, sorted by filledAt time.
     */
    public List<OrderFill> getFillsForOrder(String orderId) {
        List<OrderFillEntity> entities = orderFillJpaRepository.findByOrderId(orderId);
        return orderFillMapper.toDomainList(entities);
    }

    private void persistFill(OrderFill fill) {
        OrderFillEntity entity = orderFillMapper.toEntity(fill);
        orderFillJpaRepository.save(entity);
    }

    private int getPreviousFilledQuantity(String orderId) {
        List<OrderFillEntity> previousFills = orderFillJpaRepository.findByOrderId(orderId);
        return previousFills.stream().mapToInt(OrderFillEntity::getQuantity).sum();
    }

    private void recalculateVwap(Order order) {
        List<OrderFill> allFills = getFillsForOrder(order.getBrokerOrderId());
        BigDecimal vwap = calculateVwap(allFills);
        order.setAverageFillPrice(vwap);
    }
}
