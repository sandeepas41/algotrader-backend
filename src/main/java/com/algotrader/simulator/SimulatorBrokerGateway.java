package com.algotrader.simulator;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Position;
import com.algotrader.oms.OrderRequest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Paper trading implementation of {@link BrokerGateway} that routes all order operations
 * through the {@link VirtualOrderBook} and position queries through {@link VirtualPositionManager}.
 *
 * <p>Active when {@code algotrader.trading-mode=PAPER} or when a strategy uses
 * {@code TradingMode.PAPER}. In HYBRID mode, this gateway is used for simulated legs
 * while the live KiteBrokerGateway handles real legs.
 *
 * <p>Provides virtual margins with a configurable starting balance (default: 10 lakh).
 * Margin utilization is calculated as 15% of the notional value of all open positions.
 *
 * <p>The kill switch implementation cancels all pending orders and closes all open
 * positions at current market prices.
 */
@Service
public class SimulatorBrokerGateway implements BrokerGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatorBrokerGateway.class);

    /** Default virtual cash (10 lakh INR). */
    private static final BigDecimal VIRTUAL_CASH = new BigDecimal("1000000");

    private final VirtualOrderBook virtualOrderBook;
    private final VirtualPositionManager virtualPositionManager;

    public SimulatorBrokerGateway(VirtualOrderBook virtualOrderBook, VirtualPositionManager virtualPositionManager) {
        this.virtualOrderBook = virtualOrderBook;
        this.virtualPositionManager = virtualPositionManager;
    }

    @Override
    public String placeOrder(Order order) {
        log.debug(
                "Simulator placeOrder: {} {} {} qty={}",
                order.getSide(),
                order.getType(),
                order.getTradingSymbol(),
                order.getQuantity());
        return virtualOrderBook.placeOrder(order);
    }

    @Override
    public void modifyOrder(String brokerOrderId, Order order) {
        log.debug("Simulator modifyOrder: {}", brokerOrderId);
        virtualOrderBook.modifyOrder(brokerOrderId, order);
    }

    @Override
    public void cancelOrder(String brokerOrderId) {
        log.debug("Simulator cancelOrder: {}", brokerOrderId);
        virtualOrderBook.cancelOrder(brokerOrderId);
    }

    @Override
    public List<Order> getOrders() {
        return virtualOrderBook.getOrders();
    }

    @Override
    public List<Order> getOrderHistory(String brokerOrderId) {
        // In simulation, there's no separate history — return the current state
        return virtualOrderBook.getOrders().stream()
                .filter(o -> brokerOrderId.equals(o.getBrokerOrderId()))
                .toList();
    }

    @Override
    public Map<String, List<Position>> getPositions() {
        return virtualPositionManager.getPositions();
    }

    @Override
    public Map<String, BigDecimal> getMargins() {
        BigDecimal usedMargin = virtualPositionManager.getTotalMarginUsed();
        BigDecimal available = VIRTUAL_CASH.subtract(usedMargin);

        Map<String, BigDecimal> margins = new HashMap<>();
        margins.put("cash", VIRTUAL_CASH);
        margins.put("collateral", BigDecimal.ZERO);
        margins.put("used", usedMargin);
        margins.put("available", available.max(BigDecimal.ZERO));
        margins.put("net", VIRTUAL_CASH);
        return margins;
    }

    @Override
    public BigDecimal getOrderMargin(OrderRequest orderRequest) {
        // Simplified: assume 15% margin on notional value
        BigDecimal price = orderRequest.getPrice() != null ? orderRequest.getPrice() : BigDecimal.valueOf(100);
        return price.multiply(BigDecimal.valueOf(orderRequest.getQuantity())).multiply(new BigDecimal("0.15"));
    }

    @Override
    public BigDecimal getBasketMargin(List<OrderRequest> orderRequests) {
        // Sum individual margins with a 10% hedging discount
        BigDecimal total = orderRequests.stream().map(this::getOrderMargin).reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.multiply(new BigDecimal("0.90"));
    }

    @Override
    public int killSwitch() {
        log.warn("Simulator kill switch activated");

        int count = 0;
        // Cancel all pending orders
        for (Order order : virtualOrderBook.getOrders()) {
            if (order.getStatus() == com.algotrader.domain.enums.OrderStatus.OPEN
                    || order.getStatus() == com.algotrader.domain.enums.OrderStatus.TRIGGER_PENDING) {
                virtualOrderBook.cancelOrder(order.getBrokerOrderId());
                count++;
            }
        }

        // Close all open positions by placing MARKET orders in the opposite direction
        Map<String, List<Position>> posMap = virtualPositionManager.getPositions();
        List<Position> netPositions = posMap.getOrDefault("net", List.of());

        for (Position pos : netPositions) {
            if (pos.getQuantity() != 0) {
                Order exitOrder = Order.builder()
                        .instrumentToken(pos.getInstrumentToken())
                        .tradingSymbol(pos.getTradingSymbol())
                        .exchange(pos.getExchange())
                        .side(
                                pos.getQuantity() > 0
                                        ? com.algotrader.domain.enums.OrderSide.SELL
                                        : com.algotrader.domain.enums.OrderSide.BUY)
                        .type(com.algotrader.domain.enums.OrderType.MARKET)
                        .quantity(Math.abs(pos.getQuantity()))
                        .build();
                virtualOrderBook.placeOrder(exitOrder);
                count++;
            }
        }

        log.warn("Simulator kill switch: {} orders cancelled/placed", count);
        return count;
    }

    /**
     * Resets the simulator state (order book + positions). Called when starting a new session.
     */
    public void reset() {
        virtualOrderBook.reset();
        virtualPositionManager.reset();
        log.info("Simulator reset");
    }
}
