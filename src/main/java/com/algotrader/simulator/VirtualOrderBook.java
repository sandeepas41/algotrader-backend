package com.algotrader.simulator;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Tick;
import com.algotrader.event.OrderEvent;
import com.algotrader.event.OrderEventType;
import com.algotrader.event.TickEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Simulates order matching and execution for paper trading.
 *
 * <p>Maintains a book of pending LIMIT, SL, and SL_M orders, and checks them for fills
 * on every incoming tick. MARKET orders are filled immediately at the current last price.
 *
 * <p>Fill logic:
 * <ul>
 *   <li>LIMIT BUY: fills when LTP <= limit price (at the limit price)</li>
 *   <li>LIMIT SELL: fills when LTP >= limit price (at the limit price)</li>
 *   <li>SL BUY: triggers when LTP >= trigger price, fills at limit price (or LTP if no limit)</li>
 *   <li>SL SELL: triggers when LTP <= trigger price, fills at limit price (or LTP if no limit)</li>
 *   <li>SL_M BUY: triggers when LTP >= trigger price, fills at LTP + slippage</li>
 *   <li>SL_M SELL: triggers when LTP <= trigger price, fills at LTP - slippage</li>
 * </ul>
 *
 * <p>Slippage is configurable via {@code algotrader.simulator.slippage-bps} (default: 5 basis points).
 * This is applied as a percentage of the fill price to simulate real-world market impact.
 *
 * <p>Only responds to ticks from TickPlayer (replay) to avoid interfering with live order flow.
 */
@Service
public class VirtualOrderBook {

    private static final Logger log = LoggerFactory.getLogger(VirtualOrderBook.class);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ApplicationEventPublisher applicationEventPublisher;

    /** Pending orders indexed by internal order ID. */
    private final Map<String, Order> pendingOrders = new ConcurrentHashMap<>();

    /** Last known price per instrument (from ticks). */
    private final Map<Long, BigDecimal> lastPrices = new ConcurrentHashMap<>();

    /** All orders placed (for getOrders queries). */
    private final Map<String, Order> allOrders = new ConcurrentHashMap<>();

    /** Slippage in basis points (default: 5 bps = 0.05%). */
    @Value("${algotrader.simulator.slippage-bps:5}")
    private int slippageBps;

    public VirtualOrderBook(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Listens for replay ticks to update prices and check pending orders for fills.
     * Only processes ticks from TickPlayer (not live ticks).
     */
    @EventListener
    @org.springframework.core.annotation.Order(15)
    public void onTick(TickEvent event) {
        // Only process replay ticks (from TickPlayer)
        if (!(event.getSource() instanceof TickPlayer)) {
            return;
        }

        Tick tick = event.getTick();
        lastPrices.put(tick.getInstrumentToken(), tick.getLastPrice());
        checkOrderFills(tick);
    }

    /**
     * Places a virtual order. MARKET orders fill immediately; others are added to the pending book.
     *
     * @param order the order to place (must have instrumentToken, side, type, quantity set)
     * @return the broker order ID (simulated)
     */
    public String placeOrder(Order order) {
        String brokerOrderId = "SIM-" + UUID.randomUUID().toString().substring(0, 8);
        order.setBrokerOrderId(brokerOrderId);
        order.setStatus(OrderStatus.OPEN);
        order.setPlacedAt(LocalDateTime.now(IST));

        allOrders.put(brokerOrderId, order);

        // MARKET orders fill immediately at last known price
        if (order.getType() == OrderType.MARKET) {
            BigDecimal lastPrice = lastPrices.get(order.getInstrumentToken());
            if (lastPrice != null) {
                BigDecimal fillPrice = applySlippage(lastPrice, order.getSide());
                fillOrder(order, fillPrice);
            } else {
                // No price available yet — reject the order
                order.setStatus(OrderStatus.REJECTED);
                order.setRejectionReason("No price available for instrument " + order.getInstrumentToken());
                publishOrderEvent(order, OrderEventType.REJECTED);
                log.warn("Virtual MARKET order rejected: no price for token {}", order.getInstrumentToken());
            }
            return brokerOrderId;
        }

        // SL/SL_M orders start in TRIGGER_PENDING state
        if (order.getType() == OrderType.SL || order.getType() == OrderType.SL_M) {
            order.setStatus(OrderStatus.TRIGGER_PENDING);
        }

        pendingOrders.put(brokerOrderId, order);
        publishOrderEvent(order, OrderEventType.PLACED);

        log.debug(
                "Virtual order placed: {} {} {} qty={} @ {}",
                brokerOrderId,
                order.getSide(),
                order.getType(),
                order.getQuantity(),
                order.getPrice());

        return brokerOrderId;
    }

    /**
     * Modifies a pending order's price, trigger price, or quantity.
     */
    public void modifyOrder(String brokerOrderId, Order modifications) {
        Order existing = pendingOrders.get(brokerOrderId);
        if (existing == null) {
            throw new IllegalArgumentException("Order not found or already filled: " + brokerOrderId);
        }

        if (modifications.getPrice() != null) {
            existing.setPrice(modifications.getPrice());
        }
        if (modifications.getTriggerPrice() != null) {
            existing.setTriggerPrice(modifications.getTriggerPrice());
        }
        if (modifications.getQuantity() > 0) {
            existing.setQuantity(modifications.getQuantity());
        }

        existing.setUpdatedAt(LocalDateTime.now(IST));
        publishOrderEvent(existing, OrderEventType.MODIFIED);

        log.debug("Virtual order modified: {}", brokerOrderId);
    }

    /**
     * Cancels a pending order.
     */
    public void cancelOrder(String brokerOrderId) {
        Order order = pendingOrders.remove(brokerOrderId);
        if (order != null) {
            order.setStatus(OrderStatus.CANCELLED);
            order.setUpdatedAt(LocalDateTime.now(IST));
            publishOrderEvent(order, OrderEventType.CANCELLED);
            log.debug("Virtual order cancelled: {}", brokerOrderId);
        }
    }

    /**
     * Returns all orders placed during this simulation session.
     */
    public List<Order> getOrders() {
        return new ArrayList<>(allOrders.values());
    }

    /**
     * Resets the order book. Used when starting a new simulation session.
     */
    public void reset() {
        pendingOrders.clear();
        lastPrices.clear();
        allOrders.clear();
    }

    /**
     * Returns the number of pending orders.
     */
    public int getPendingOrderCount() {
        return pendingOrders.size();
    }

    // ---- Internal matching logic ----

    private void checkOrderFills(Tick tick) {
        BigDecimal ltp = tick.getLastPrice();
        Long token = tick.getInstrumentToken();

        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, Order> entry : pendingOrders.entrySet()) {
            Order order = entry.getValue();

            if (!token.equals(order.getInstrumentToken())) {
                continue;
            }

            BigDecimal fillPrice = tryMatch(order, ltp);
            if (fillPrice != null) {
                fillOrder(order, fillPrice);
                toRemove.add(entry.getKey());
            }
        }

        toRemove.forEach(pendingOrders::remove);
    }

    /**
     * Attempts to match an order against the current LTP.
     * Returns the fill price if matched, null otherwise.
     */
    private BigDecimal tryMatch(Order order, BigDecimal ltp) {
        return switch (order.getType()) {
            case LIMIT -> matchLimit(order, ltp);
            case SL -> matchStopLoss(order, ltp);
            case SL_M -> matchStopLossMarket(order, ltp);
            case MARKET -> ltp; // Should not reach here — handled in placeOrder
        };
    }

    /**
     * LIMIT order matching:
     * BUY fills when LTP <= limit price (at limit price — better or equal execution).
     * SELL fills when LTP >= limit price (at limit price).
     */
    private BigDecimal matchLimit(Order order, BigDecimal ltp) {
        if (order.getSide() == OrderSide.BUY && ltp.compareTo(order.getPrice()) <= 0) {
            return order.getPrice();
        }
        if (order.getSide() == OrderSide.SELL && ltp.compareTo(order.getPrice()) >= 0) {
            return order.getPrice();
        }
        return null;
    }

    /**
     * SL (stop-loss limit) order matching:
     * BUY triggers when LTP >= trigger price, fills at limit price.
     * SELL triggers when LTP <= trigger price, fills at limit price.
     */
    private BigDecimal matchStopLoss(Order order, BigDecimal ltp) {
        if (order.getSide() == OrderSide.BUY && ltp.compareTo(order.getTriggerPrice()) >= 0) {
            return order.getPrice() != null ? order.getPrice() : ltp;
        }
        if (order.getSide() == OrderSide.SELL && ltp.compareTo(order.getTriggerPrice()) <= 0) {
            return order.getPrice() != null ? order.getPrice() : ltp;
        }
        return null;
    }

    /**
     * SL_M (stop-loss market) order matching:
     * BUY triggers when LTP >= trigger price, fills at LTP + slippage.
     * SELL triggers when LTP <= trigger price, fills at LTP - slippage.
     */
    private BigDecimal matchStopLossMarket(Order order, BigDecimal ltp) {
        if (order.getSide() == OrderSide.BUY && ltp.compareTo(order.getTriggerPrice()) >= 0) {
            return applySlippage(ltp, OrderSide.BUY);
        }
        if (order.getSide() == OrderSide.SELL && ltp.compareTo(order.getTriggerPrice()) <= 0) {
            return applySlippage(ltp, OrderSide.SELL);
        }
        return null;
    }

    private void fillOrder(Order order, BigDecimal fillPrice) {
        order.setStatus(OrderStatus.COMPLETE);
        order.setFilledQuantity(order.getQuantity());
        order.setAverageFillPrice(fillPrice);
        order.setUpdatedAt(LocalDateTime.now(IST));

        publishOrderEvent(order, OrderEventType.FILLED);

        log.debug(
                "Virtual order filled: {} {} {} qty={} @ {}",
                order.getBrokerOrderId(),
                order.getSide(),
                order.getTradingSymbol(),
                order.getQuantity(),
                fillPrice);
    }

    /**
     * Applies slippage to a price. BUY orders get a slightly higher fill price,
     * SELL orders get a slightly lower fill price.
     *
     * @param price the base price
     * @param side  BUY or SELL
     * @return the price with slippage applied
     */
    BigDecimal applySlippage(BigDecimal price, OrderSide side) {
        if (slippageBps <= 0) {
            return price;
        }

        // slippageBps basis points = slippageBps / 10000 as a multiplier
        BigDecimal slippageMultiplier =
                BigDecimal.valueOf(slippageBps).divide(BigDecimal.valueOf(10000), 6, RoundingMode.HALF_UP);
        BigDecimal slippage = price.multiply(slippageMultiplier);

        return side == OrderSide.BUY
                ? price.add(slippage).setScale(2, RoundingMode.HALF_UP)
                : price.subtract(slippage).setScale(2, RoundingMode.HALF_UP);
    }

    private void publishOrderEvent(Order order, OrderEventType eventType) {
        try {
            applicationEventPublisher.publishEvent(new OrderEvent(this, order, eventType));
        } catch (Exception e) {
            log.error("Failed to publish virtual order event: {}", e.getMessage());
        }
    }
}
