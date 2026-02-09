package com.algotrader.unit.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Tick;
import com.algotrader.event.OrderEvent;
import com.algotrader.event.OrderEventType;
import com.algotrader.event.TickEvent;
import com.algotrader.simulator.VirtualOrderBook;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for VirtualOrderBook (Task 17.2).
 *
 * <p>Verifies: MARKET fill, LIMIT fill logic, SL trigger/fill, SL_M trigger/fill with slippage,
 * order cancellation, modify, and order sourcing filter (only replay ticks).
 */
class VirtualOrderBookTest {

    private VirtualOrderBook virtualOrderBook;
    private TestEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        eventPublisher = new TestEventPublisher();
        virtualOrderBook = new VirtualOrderBook(eventPublisher);
        ReflectionTestUtils.setField(virtualOrderBook, "slippageBps", 5);
    }

    @Test
    void marketOrder_fillsImmediately() {
        // Set a price first via tick
        publishReplayTick(100L, BigDecimal.valueOf(250.0));

        Order order = createOrder(100L, OrderSide.BUY, OrderType.MARKET, 50, null, null);
        String orderId = virtualOrderBook.placeOrder(order);

        assertThat(orderId).startsWith("SIM-");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETE);
        assertThat(order.getFilledQuantity()).isEqualTo(50);
        assertThat(order.getAverageFillPrice()).isNotNull();
    }

    @Test
    void marketOrder_rejectedWhenNoPriceAvailable() {
        Order order = createOrder(999L, OrderSide.BUY, OrderType.MARKET, 50, null, null);
        virtualOrderBook.placeOrder(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectionReason()).contains("No price available");
    }

    @Test
    void limitBuy_fillsWhenPriceCrossesLimit() {
        publishReplayTick(100L, BigDecimal.valueOf(260.0));

        Order order = createOrder(100L, OrderSide.BUY, OrderType.LIMIT, 50, BigDecimal.valueOf(250.0), null);
        virtualOrderBook.placeOrder(order);

        // Price is 260, limit is 250 — should NOT fill yet
        assertThat(order.getStatus()).isEqualTo(OrderStatus.OPEN);

        // Price drops to 245 (below limit) — should fill at 250 (limit price)
        publishReplayTick(100L, BigDecimal.valueOf(245.0));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETE);
        assertThat(order.getAverageFillPrice()).isEqualByComparingTo(BigDecimal.valueOf(250.0));
    }

    @Test
    void limitSell_fillsWhenPriceCrossesLimit() {
        publishReplayTick(100L, BigDecimal.valueOf(240.0));

        Order order = createOrder(100L, OrderSide.SELL, OrderType.LIMIT, 50, BigDecimal.valueOf(250.0), null);
        virtualOrderBook.placeOrder(order);

        // Price is 240, limit is 250 — should NOT fill yet
        assertThat(order.getStatus()).isEqualTo(OrderStatus.OPEN);

        // Price rises to 255 (above limit) — should fill at 250 (limit price)
        publishReplayTick(100L, BigDecimal.valueOf(255.0));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETE);
        assertThat(order.getAverageFillPrice()).isEqualByComparingTo(BigDecimal.valueOf(250.0));
    }

    @Test
    void slBuy_triggersAtStopPriceAndFillsAtLimit() {
        publishReplayTick(100L, BigDecimal.valueOf(240.0));

        // SL BUY: trigger at 250, limit at 252
        Order order = createOrder(
                100L, OrderSide.BUY, OrderType.SL, 50, BigDecimal.valueOf(252.0), BigDecimal.valueOf(250.0));
        virtualOrderBook.placeOrder(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.TRIGGER_PENDING);

        // Price at 248 — below trigger, should not fill
        publishReplayTick(100L, BigDecimal.valueOf(248.0));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.TRIGGER_PENDING);

        // Price at 251 — above trigger (250) — should fill at limit price (252)
        publishReplayTick(100L, BigDecimal.valueOf(251.0));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETE);
        assertThat(order.getAverageFillPrice()).isEqualByComparingTo(BigDecimal.valueOf(252.0));
    }

    @Test
    void slSell_triggersAtStopPriceAndFillsAtLimit() {
        publishReplayTick(100L, BigDecimal.valueOf(260.0));

        // SL SELL: trigger at 250, limit at 248
        Order order = createOrder(
                100L, OrderSide.SELL, OrderType.SL, 50, BigDecimal.valueOf(248.0), BigDecimal.valueOf(250.0));
        virtualOrderBook.placeOrder(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.TRIGGER_PENDING);

        // Price at 252 — above trigger, should not fill
        publishReplayTick(100L, BigDecimal.valueOf(252.0));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.TRIGGER_PENDING);

        // Price at 249 — below trigger (250) — should fill at limit price (248)
        publishReplayTick(100L, BigDecimal.valueOf(249.0));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETE);
        assertThat(order.getAverageFillPrice()).isEqualByComparingTo(BigDecimal.valueOf(248.0));
    }

    @Test
    void slmBuy_triggersAtStopPriceWithSlippage() {
        publishReplayTick(100L, BigDecimal.valueOf(240.0));

        // SL_M BUY: trigger at 250
        Order order = createOrder(100L, OrderSide.BUY, OrderType.SL_M, 50, null, BigDecimal.valueOf(250.0));
        virtualOrderBook.placeOrder(order);

        // Price at 255 — above trigger — should fill at 255 + slippage
        publishReplayTick(100L, BigDecimal.valueOf(255.0));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETE);
        // 5 bps slippage on 255 = 255 * 0.0005 = 0.1275 -> fill = 255.13
        assertThat(order.getAverageFillPrice()).isGreaterThan(BigDecimal.valueOf(255.0));
    }

    @Test
    void slmSell_triggersAtStopPriceWithSlippage() {
        publishReplayTick(100L, BigDecimal.valueOf(260.0));

        // SL_M SELL: trigger at 250
        Order order = createOrder(100L, OrderSide.SELL, OrderType.SL_M, 50, null, BigDecimal.valueOf(250.0));
        virtualOrderBook.placeOrder(order);

        // Price at 248 — below trigger — should fill at 248 - slippage
        publishReplayTick(100L, BigDecimal.valueOf(248.0));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETE);
        assertThat(order.getAverageFillPrice()).isLessThan(BigDecimal.valueOf(248.0));
    }

    @Test
    void slippageModel_addsBasisPointsOnMarketOrder() {
        // Set price to 100.0 via replay tick
        publishReplayTick(100L, BigDecimal.valueOf(100.0));

        // Place a MARKET BUY — should fill at 100 + slippage (5 bps = 0.05%)
        Order buyOrder = createOrder(100L, OrderSide.BUY, OrderType.MARKET, 50, null, null);
        virtualOrderBook.placeOrder(buyOrder);

        // Fill price should be 100.05 (100 + 0.05% slippage)
        assertThat(buyOrder.getAverageFillPrice()).isGreaterThan(BigDecimal.valueOf(100.0));
        assertThat(buyOrder.getAverageFillPrice()).isEqualByComparingTo(new BigDecimal("100.05"));

        // Place a MARKET SELL — should fill at 100 - slippage
        Order sellOrder = createOrder(100L, OrderSide.SELL, OrderType.MARKET, 50, null, null);
        virtualOrderBook.placeOrder(sellOrder);

        assertThat(sellOrder.getAverageFillPrice()).isLessThan(BigDecimal.valueOf(100.0));
        assertThat(sellOrder.getAverageFillPrice()).isEqualByComparingTo(new BigDecimal("99.95"));
    }

    @Test
    void cancelOrder_removesPendingOrder() {
        publishReplayTick(100L, BigDecimal.valueOf(260.0));

        Order order = createOrder(100L, OrderSide.BUY, OrderType.LIMIT, 50, BigDecimal.valueOf(250.0), null);
        String orderId = virtualOrderBook.placeOrder(order);

        assertThat(virtualOrderBook.getPendingOrderCount()).isEqualTo(1);

        virtualOrderBook.cancelOrder(orderId);

        assertThat(virtualOrderBook.getPendingOrderCount()).isEqualTo(0);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void modifyOrder_updatesPriceAndQuantity() {
        publishReplayTick(100L, BigDecimal.valueOf(260.0));

        Order order = createOrder(100L, OrderSide.BUY, OrderType.LIMIT, 50, BigDecimal.valueOf(250.0), null);
        String orderId = virtualOrderBook.placeOrder(order);

        Order modifications =
                Order.builder().price(BigDecimal.valueOf(245.0)).quantity(75).build();
        virtualOrderBook.modifyOrder(orderId, modifications);

        assertThat(order.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(245.0));
        assertThat(order.getQuantity()).isEqualTo(75);
    }

    @Test
    void onTick_ignoresNonReplayTicks() {
        publishReplayTick(100L, BigDecimal.valueOf(260.0));

        Order order = createOrder(100L, OrderSide.BUY, OrderType.LIMIT, 50, BigDecimal.valueOf(240.0), null);
        virtualOrderBook.placeOrder(order);

        // Publish a non-replay tick (source is "this" not TickPlayer)
        Tick tick = createTick(100L, BigDecimal.valueOf(235.0));
        TickEvent nonReplayEvent = new TickEvent("non-replay-source", tick);
        virtualOrderBook.onTick(nonReplayEvent);

        // Order should NOT have been filled (non-replay tick ignored)
        assertThat(order.getStatus()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void getOrders_returnsAllPlacedOrders() {
        publishReplayTick(100L, BigDecimal.valueOf(250.0));

        virtualOrderBook.placeOrder(createOrder(100L, OrderSide.BUY, OrderType.MARKET, 50, null, null));
        virtualOrderBook.placeOrder(
                createOrder(100L, OrderSide.SELL, OrderType.LIMIT, 30, BigDecimal.valueOf(260.0), null));

        assertThat(virtualOrderBook.getOrders()).hasSize(2);
    }

    @Test
    void reset_clearsAllState() {
        publishReplayTick(100L, BigDecimal.valueOf(250.0));

        virtualOrderBook.placeOrder(createOrder(100L, OrderSide.BUY, OrderType.MARKET, 50, null, null));
        virtualOrderBook.placeOrder(
                createOrder(100L, OrderSide.SELL, OrderType.LIMIT, 30, BigDecimal.valueOf(260.0), null));

        virtualOrderBook.reset();

        assertThat(virtualOrderBook.getOrders()).isEmpty();
        assertThat(virtualOrderBook.getPendingOrderCount()).isEqualTo(0);
    }

    @Test
    void fillOrder_publishesOrderEvent() {
        publishReplayTick(100L, BigDecimal.valueOf(250.0));

        Order order = createOrder(100L, OrderSide.BUY, OrderType.MARKET, 50, null, null);
        virtualOrderBook.placeOrder(order);

        long filledEvents = eventPublisher.events.stream()
                .filter(e -> e instanceof OrderEvent)
                .map(e -> (OrderEvent) e)
                .filter(e -> e.getEventType() == OrderEventType.FILLED)
                .count();
        assertThat(filledEvents).isEqualTo(1);
    }

    // ---- Helpers ----

    private void publishReplayTick(long instrumentToken, BigDecimal price) {
        Tick tick = createTick(instrumentToken, price);
        // Use a mock TickPlayer-like source to pass the instanceof check
        TickEvent event = new TickEvent(new FakeTickPlayer(), tick);
        virtualOrderBook.onTick(event);
    }

    private Tick createTick(long instrumentToken, BigDecimal price) {
        return Tick.builder()
                .instrumentToken(instrumentToken)
                .lastPrice(price)
                .open(price)
                .high(price)
                .low(price)
                .close(price)
                .volume(1000)
                .timestamp(LocalDateTime.now())
                .build();
    }

    private Order createOrder(
            long instrumentToken, OrderSide side, OrderType type, int quantity, BigDecimal price, BigDecimal trigger) {
        return Order.builder()
                .instrumentToken(instrumentToken)
                .tradingSymbol("TEST-SYMBOL")
                .exchange("NFO")
                .side(side)
                .type(type)
                .quantity(quantity)
                .price(price)
                .triggerPrice(trigger)
                .build();
    }

    /** Fake TickPlayer for instanceof checks in VirtualOrderBook.onTick. */
    static class FakeTickPlayer extends com.algotrader.simulator.TickPlayer {
        FakeTickPlayer() {
            super(null, null, null);
        }
    }

    static class TestEventPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }
    }
}
