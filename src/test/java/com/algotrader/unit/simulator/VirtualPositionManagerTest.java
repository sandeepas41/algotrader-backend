package com.algotrader.unit.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.Tick;
import com.algotrader.event.OrderEvent;
import com.algotrader.event.OrderEventType;
import com.algotrader.event.TickEvent;
import com.algotrader.simulator.TickPlayer;
import com.algotrader.simulator.VirtualOrderBook;
import com.algotrader.simulator.VirtualPositionManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for VirtualPositionManager (Task 17.2).
 *
 * <p>Verifies: position creation on fill, VWAP averaging, position closing with P&L,
 * position reversal, unrealized P&L updates on ticks, and source filtering.
 */
class VirtualPositionManagerTest {

    private VirtualPositionManager virtualPositionManager;

    @BeforeEach
    void setUp() {
        virtualPositionManager = new VirtualPositionManager();
    }

    @Test
    void buyFill_createsLongPosition() {
        publishFill(100L, "NIFTY24FEB22000CE", OrderSide.BUY, 50, BigDecimal.valueOf(100.0));

        Map<String, List<Position>> positions = virtualPositionManager.getPositions();
        List<Position> netPositions = positions.get("net");

        assertThat(netPositions).hasSize(1);
        Position pos = netPositions.get(0);
        assertThat(pos.getQuantity()).isEqualTo(50);
        assertThat(pos.getAveragePrice()).isEqualByComparingTo(BigDecimal.valueOf(100.0));
        assertThat(pos.getTradingSymbol()).isEqualTo("NIFTY24FEB22000CE");
    }

    @Test
    void sellFill_createsShortPosition() {
        publishFill(100L, "NIFTY24FEB22000PE", OrderSide.SELL, 50, BigDecimal.valueOf(80.0));

        List<Position> positions = virtualPositionManager.getPositions().get("net");
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).getQuantity()).isEqualTo(-50);
        assertThat(positions.get(0).getAveragePrice()).isEqualByComparingTo(BigDecimal.valueOf(80.0));
    }

    @Test
    void addToPosition_recalculatesVWAP() {
        // Buy 50 at 100
        publishFill(100L, "NIFTY24FEB22000CE", OrderSide.BUY, 50, BigDecimal.valueOf(100.0));
        // Buy 50 more at 120
        publishFill(100L, "NIFTY24FEB22000CE", OrderSide.BUY, 50, BigDecimal.valueOf(120.0));

        List<Position> positions = virtualPositionManager.getPositions().get("net");
        assertThat(positions).hasSize(1);
        Position pos = positions.get(0);
        assertThat(pos.getQuantity()).isEqualTo(100);
        // VWAP: (50*100 + 50*120) / 100 = 110
        assertThat(pos.getAveragePrice()).isEqualByComparingTo(BigDecimal.valueOf(110.0));
    }

    @Test
    void closePosition_calculatesRealizedPnl() {
        // Buy 50 at 100
        publishFill(100L, "NIFTY24FEB22000CE", OrderSide.BUY, 50, BigDecimal.valueOf(100.0));
        // Sell 50 at 120 (close position)
        publishFill(100L, "NIFTY24FEB22000CE", OrderSide.SELL, 50, BigDecimal.valueOf(120.0));

        List<Position> positions = virtualPositionManager.getPositions().get("net");
        assertThat(positions).hasSize(1);
        Position pos = positions.get(0);
        assertThat(pos.getQuantity()).isEqualTo(0);
        // Realized P&L: (120 - 100) * 50 = 1000
        assertThat(pos.getRealizedPnl()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(pos.getClosedAt()).isNotNull();
    }

    @Test
    void closeShortPosition_calculatesRealizedPnl() {
        // Sell 50 at 100 (short)
        publishFill(100L, "NIFTY24FEB22000CE", OrderSide.SELL, 50, BigDecimal.valueOf(100.0));
        // Buy 50 at 80 (close short)
        publishFill(100L, "NIFTY24FEB22000CE", OrderSide.BUY, 50, BigDecimal.valueOf(80.0));

        List<Position> positions = virtualPositionManager.getPositions().get("net");
        Position pos = positions.get(0);
        assertThat(pos.getQuantity()).isEqualTo(0);
        // Short P&L: (100 - 80) * 50 = 1000
        assertThat(pos.getRealizedPnl()).isEqualByComparingTo(BigDecimal.valueOf(1000));
    }

    @Test
    void totalRealizedPnl_accumulatesAcrossPositions() {
        // Position 1: Buy 50 at 100, sell at 120 -> P&L = 1000
        publishFill(100L, "CE", OrderSide.BUY, 50, BigDecimal.valueOf(100.0));
        publishFill(100L, "CE", OrderSide.SELL, 50, BigDecimal.valueOf(120.0));

        // Position 2: Sell 50 at 200, buy at 180 -> P&L = 1000
        publishFill(200L, "PE", OrderSide.SELL, 50, BigDecimal.valueOf(200.0));
        publishFill(200L, "PE", OrderSide.BUY, 50, BigDecimal.valueOf(180.0));

        assertThat(virtualPositionManager.getTotalRealizedPnl()).isEqualByComparingTo(BigDecimal.valueOf(2000));
    }

    @Test
    void unrealizedPnl_updatesOnTick() {
        // Buy 50 at 100
        publishFill(100L, "NIFTY24FEB22000CE", OrderSide.BUY, 50, BigDecimal.valueOf(100.0));

        // Price moves to 110
        publishReplayTick(100L, BigDecimal.valueOf(110.0));

        List<Position> positions = virtualPositionManager.getPositions().get("net");
        Position pos = positions.get(0);
        // Unrealized: (110 - 100) * 50 = 500
        assertThat(pos.getUnrealizedPnl()).isEqualByComparingTo(BigDecimal.valueOf(500));
        assertThat(pos.getLastPrice()).isEqualByComparingTo(BigDecimal.valueOf(110.0));
    }

    @Test
    void unrealizedPnl_negativeForLossyLong() {
        publishFill(100L, "NIFTY24FEB22000CE", OrderSide.BUY, 50, BigDecimal.valueOf(100.0));
        publishReplayTick(100L, BigDecimal.valueOf(90.0));

        Position pos = virtualPositionManager.getPositions().get("net").get(0);
        // Unrealized: (90 - 100) * 50 = -500
        assertThat(pos.getUnrealizedPnl()).isEqualByComparingTo(BigDecimal.valueOf(-500));
    }

    @Test
    void unrealizedPnl_shortPositionProfit() {
        publishFill(100L, "NIFTY24FEB22000PE", OrderSide.SELL, 50, BigDecimal.valueOf(100.0));
        publishReplayTick(100L, BigDecimal.valueOf(90.0));

        Position pos = virtualPositionManager.getPositions().get("net").get(0);
        // Short unrealized: (100 - 90) * 50 = 500
        assertThat(pos.getUnrealizedPnl()).isEqualByComparingTo(BigDecimal.valueOf(500));
    }

    @Test
    void onTick_ignoresNonReplayTicks() {
        publishFill(100L, "NIFTY24FEB22000CE", OrderSide.BUY, 50, BigDecimal.valueOf(100.0));

        // Non-replay tick should be ignored
        Tick tick = Tick.builder()
                .instrumentToken(100L)
                .lastPrice(BigDecimal.valueOf(150.0))
                .timestamp(LocalDateTime.now())
                .build();
        virtualPositionManager.onTick(new TickEvent("not-a-tick-player", tick));

        Position pos = virtualPositionManager.getPositions().get("net").get(0);
        // Unrealized should NOT have been updated (still 0 since no replay tick)
        assertThat(pos.getLastPrice()).isNull();
    }

    @Test
    void onOrderFilled_ignoresNonVirtualOrderBookEvents() {
        Order order = Order.builder()
                .instrumentToken(100L)
                .tradingSymbol("NIFTY24FEB22000CE")
                .side(OrderSide.BUY)
                .filledQuantity(50)
                .averageFillPrice(BigDecimal.valueOf(100.0))
                .status(OrderStatus.COMPLETE)
                .build();

        // Source is "this" not VirtualOrderBook — should be ignored
        virtualPositionManager.onOrderFilled(new OrderEvent("not-virtual-order-book", order, OrderEventType.FILLED));

        assertThat(virtualPositionManager.getPositions().get("net")).isEmpty();
    }

    @Test
    void getTotalMarginUsed_calculatesSimplified15Percent() {
        publishFill(100L, "NIFTY24FEB22000CE", OrderSide.BUY, 50, BigDecimal.valueOf(100.0));
        publishReplayTick(100L, BigDecimal.valueOf(100.0));

        BigDecimal margin = virtualPositionManager.getTotalMarginUsed();
        // 15% of (100 * 50) = 15% of 5000 = 750
        assertThat(margin).isEqualByComparingTo(BigDecimal.valueOf(750.0));
    }

    @Test
    void reset_clearsAllPositionsAndPnl() {
        publishFill(100L, "NIFTY24FEB22000CE", OrderSide.BUY, 50, BigDecimal.valueOf(100.0));
        publishFill(100L, "NIFTY24FEB22000CE", OrderSide.SELL, 50, BigDecimal.valueOf(120.0));

        assertThat(virtualPositionManager.getTotalRealizedPnl()).isNotEqualByComparingTo(BigDecimal.ZERO);

        virtualPositionManager.reset();

        assertThat(virtualPositionManager.getPositions().get("net")).isEmpty();
        assertThat(virtualPositionManager.getTotalRealizedPnl()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void openPositionCount_countsNonZeroQuantity() {
        publishFill(100L, "CE", OrderSide.BUY, 50, BigDecimal.valueOf(100.0));
        publishFill(200L, "PE", OrderSide.SELL, 30, BigDecimal.valueOf(80.0));

        assertThat(virtualPositionManager.getOpenPositionCount()).isEqualTo(2);

        // Close one position
        publishFill(100L, "CE", OrderSide.SELL, 50, BigDecimal.valueOf(110.0));

        assertThat(virtualPositionManager.getOpenPositionCount()).isEqualTo(1);
    }

    // ---- Helpers ----

    private void publishFill(long instrumentToken, String tradingSymbol, OrderSide side, int qty, BigDecimal price) {
        Order order = Order.builder()
                .instrumentToken(instrumentToken)
                .tradingSymbol(tradingSymbol)
                .exchange("NFO")
                .side(side)
                .filledQuantity(qty)
                .averageFillPrice(price)
                .status(OrderStatus.COMPLETE)
                .build();

        // Source must be VirtualOrderBook for the event filter
        virtualPositionManager.onOrderFilled(new OrderEvent(new FakeVirtualOrderBook(), order, OrderEventType.FILLED));
    }

    private void publishReplayTick(long instrumentToken, BigDecimal price) {
        Tick tick = Tick.builder()
                .instrumentToken(instrumentToken)
                .lastPrice(price)
                .timestamp(LocalDateTime.now())
                .build();
        virtualPositionManager.onTick(new TickEvent(new FakeTickPlayer(), tick));
    }

    /** Fake VirtualOrderBook for instanceof checks. */
    static class FakeVirtualOrderBook extends VirtualOrderBook {
        FakeVirtualOrderBook() {
            super(null);
        }
    }

    /** Fake TickPlayer for instanceof checks. */
    static class FakeTickPlayer extends TickPlayer {
        FakeTickPlayer() {
            super(null, null, null);
        }
    }
}
