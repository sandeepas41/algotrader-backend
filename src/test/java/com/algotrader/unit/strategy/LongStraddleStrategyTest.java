package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Position;
import com.algotrader.oms.OrderRequest;
import com.algotrader.strategy.base.MarketSnapshot;
import com.algotrader.strategy.impl.LongStraddleConfig;
import com.algotrader.strategy.impl.LongStraddleStrategy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for LongStraddleStrategy covering identity, dual-mode monitoring intervals,
 * entry conditions (window + IV), 2-leg BUY order construction, both exit modes
 * (scalping point-based and positional %-based), no-op adjustment, and no morphing.
 */
class LongStraddleStrategyTest {

    private static final String STRATEGY_ID = "STR-LST-001";
    private static final String STRATEGY_NAME = "NIFTY-LongStraddle-Test";

    private LongStraddleConfig scalpingConfig;
    private TestableLongStraddleStrategy scalpingStrategy;

    @BeforeEach
    void setUp() {
        scalpingConfig = LongStraddleConfig.builder()
                .underlying("NIFTY")
                .lots(1)
                .strikeInterval(BigDecimal.valueOf(50))
                .autoEntry(true)
                .scalpingMode(true)
                .minIV(BigDecimal.valueOf(15))
                .targetPoints(BigDecimal.valueOf(30))
                .stopLossPoints(BigDecimal.valueOf(15))
                .maxHoldDuration(Duration.ofMinutes(10))
                .entryStartTime(LocalTime.of(9, 20))
                .entryEndTime(LocalTime.of(15, 15))
                .build();

        scalpingStrategy = new TestableLongStraddleStrategy(STRATEGY_ID, STRATEGY_NAME, scalpingConfig);
    }

    // ========================
    // IDENTITY & TYPE
    // ========================

    @Nested
    @DisplayName("Identity & Type")
    class IdentityAndType {

        @Test
        @DisplayName("Type is LONG_STRADDLE")
        void typeIsLongStraddle() {
            assertThat(scalpingStrategy.getType()).isEqualTo(StrategyType.LONG_STRADDLE);
        }

        @Test
        @DisplayName("Scalping mode: tick-level monitoring")
        void scalpingMonitoringInterval() {
            assertThat(scalpingStrategy.getMonitoringInterval()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("Scalping mode: 2s stale data threshold")
        void scalpingStaleDataThreshold() {
            assertThat(scalpingStrategy.getStaleDataThreshold()).isEqualTo(Duration.ofSeconds(2));
        }

        @Test
        @DisplayName("Positional mode: 5-min monitoring interval")
        void positionalMonitoringInterval() {
            TestableLongStraddleStrategy positional = createPositionalStrategy();
            assertThat(positional.getMonitoringInterval()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("Positional mode: 5s stale data threshold (default)")
        void positionalStaleDataThreshold() {
            TestableLongStraddleStrategy positional = createPositionalStrategy();
            assertThat(positional.getStaleDataThreshold()).isEqualTo(Duration.ofSeconds(5));
        }
    }

    // ========================
    // ENTRY CONDITIONS
    // ========================

    @Nested
    @DisplayName("Entry Conditions")
    class EntryConditions {

        @Test
        @DisplayName("Returns true when autoEntry enabled, within window, IV >= minIV")
        void entryWhenAllConditionsMet() {
            scalpingStrategy.forceWithinEntryWindow(true);
            MarketSnapshot snapshot = createSnapshot(22000, BigDecimal.valueOf(18));

            assertThat(scalpingStrategy.callShouldEnter(snapshot)).isTrue();
        }

        @Test
        @DisplayName("Returns false when autoEntry disabled")
        void noEntryWhenAutoEntryDisabled() {
            LongStraddleConfig manualConfig = LongStraddleConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .autoEntry(false)
                    .scalpingMode(true)
                    .build();

            TestableLongStraddleStrategy manualStrategy =
                    new TestableLongStraddleStrategy("STR-LST-MAN", "Manual", manualConfig);
            manualStrategy.forceWithinEntryWindow(true);

            assertThat(manualStrategy.callShouldEnter(createSnapshot(22000, BigDecimal.valueOf(18))))
                    .isFalse();
        }

        @Test
        @DisplayName("Returns false when outside entry window")
        void noEntryOutsideWindow() {
            scalpingStrategy.forceWithinEntryWindow(false);
            assertThat(scalpingStrategy.callShouldEnter(createSnapshot(22000, BigDecimal.valueOf(18))))
                    .isFalse();
        }

        @Test
        @DisplayName("Returns false when IV below minimum")
        void noEntryWhenIVBelowMin() {
            scalpingStrategy.forceWithinEntryWindow(true);
            MarketSnapshot snapshot = createSnapshot(22000, BigDecimal.valueOf(10));

            assertThat(scalpingStrategy.callShouldEnter(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Returns false when IV data is null")
        void noEntryWhenIVNull() {
            scalpingStrategy.forceWithinEntryWindow(true);
            MarketSnapshot snapshot = createSnapshot(22000, null);

            assertThat(scalpingStrategy.callShouldEnter(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Returns true when minIV is null (no IV filter)")
        void entryWhenMinIVNull() {
            LongStraddleConfig noIVConfig = LongStraddleConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .autoEntry(true)
                    .scalpingMode(true)
                    .minIV(null)
                    .build();

            TestableLongStraddleStrategy noIVStrategy =
                    new TestableLongStraddleStrategy("STR-LST-NIV", "NoIV", noIVConfig);
            noIVStrategy.forceWithinEntryWindow(true);

            assertThat(noIVStrategy.callShouldEnter(createSnapshot(22000, null)))
                    .isTrue();
        }
    }

    // ========================
    // ENTRY ORDER CONSTRUCTION (2 BUY LEGS)
    // ========================

    @Nested
    @DisplayName("Entry Order Construction (2 BUY legs)")
    class EntryOrderConstruction {

        @Test
        @DisplayName("Produces exactly 2 legs")
        void twoLegs() {
            List<OrderRequest> orders = scalpingStrategy.callBuildEntryOrders(createSnapshot(22000));
            assertThat(orders).hasSize(2);
        }

        @Test
        @DisplayName("Both legs are BUY (opposite of short straddle)")
        void bothLegsBuy() {
            List<OrderRequest> orders = scalpingStrategy.callBuildEntryOrders(createSnapshot(22000));

            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.BUY);
            assertThat(orders.get(1).getSide()).isEqualTo(OrderSide.BUY);
        }

        @Test
        @DisplayName("Leg 1 = CE, Leg 2 = PE at ATM strike")
        void ceAndPeAtATM() {
            List<OrderRequest> orders = scalpingStrategy.callBuildEntryOrders(createSnapshot(22000));

            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22000CE");
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY22000PE");
        }

        @Test
        @DisplayName("ATM rounds to nearest strike interval")
        void atmRoundsToStrikeInterval() {
            List<OrderRequest> orders = scalpingStrategy.callBuildEntryOrders(createSnapshot(22035));

            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22050CE");
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY22050PE");
        }

        @Test
        @DisplayName("All legs are MARKET orders with quantity 1")
        void marketOrdersQuantity1() {
            List<OrderRequest> orders = scalpingStrategy.callBuildEntryOrders(createSnapshot(22000));

            for (OrderRequest order : orders) {
                assertThat(order.getQuantity()).isEqualTo(1);
                assertThat(order.getType()).isEqualTo(com.algotrader.domain.enums.OrderType.MARKET);
            }
        }
    }

    // ========================
    // EXIT CONDITIONS (SCALPING MODE)
    // ========================

    @Nested
    @DisplayName("Scalping Exit Conditions")
    class ScalpingExitConditions {

        @Test
        @DisplayName("No exit when no positions")
        void noExitWhenNoPositions() {
            assertThat(scalpingStrategy.callShouldExit(createSnapshot(22000))).isFalse();
        }

        @Test
        @DisplayName("Exit on target: P&L >= 30 points")
        void exitOnTargetPoints() {
            addPositionWithPnl(scalpingStrategy, BigDecimal.valueOf(30));
            assertThat(scalpingStrategy.callShouldExit(createSnapshot(22000))).isTrue();
        }

        @Test
        @DisplayName("No exit when P&L below target")
        void noExitBelowTarget() {
            addPositionWithPnl(scalpingStrategy, BigDecimal.valueOf(20));
            assertThat(scalpingStrategy.callShouldExit(createSnapshot(22000))).isFalse();
        }

        @Test
        @DisplayName("Exit on stop loss: P&L <= -15 points")
        void exitOnStopLoss() {
            addPositionWithPnl(scalpingStrategy, BigDecimal.valueOf(-15));
            assertThat(scalpingStrategy.callShouldExit(createSnapshot(22000))).isTrue();
        }

        @Test
        @DisplayName("Exit on max hold duration exceeded")
        void exitOnMaxHoldDuration() {
            scalpingStrategy.forceEntryTime(LocalDateTime.now().minusMinutes(11));
            addPositionWithPnl(scalpingStrategy, BigDecimal.valueOf(5));
            assertThat(scalpingStrategy.callShouldExit(createSnapshot(22000))).isTrue();
        }
    }

    // ========================
    // EXIT CONDITIONS (POSITIONAL MODE)
    // ========================

    @Nested
    @DisplayName("Positional Exit Conditions")
    class PositionalExitConditions {

        @Test
        @DisplayName("Positional exit: target profit reached")
        void positionalExitOnTarget() {
            TestableLongStraddleStrategy positional = createPositionalStrategy();
            positional.setEntryPremium(BigDecimal.valueOf(200));
            addPositionWithPnl(positional, BigDecimal.valueOf(100)); // 50% of entry premium

            assertThat(positional.callShouldExit(createSnapshot(22000))).isTrue();
        }

        @Test
        @DisplayName("Positional exit: stop loss hit")
        void positionalExitOnStopLoss() {
            TestableLongStraddleStrategy positional = createPositionalStrategy();
            positional.setEntryPremium(BigDecimal.valueOf(200));
            // stopLossMultiplier = 0.5 -> stop at -100
            addPositionWithPnl(positional, BigDecimal.valueOf(-100));

            assertThat(positional.callShouldExit(createSnapshot(22000))).isTrue();
        }

        @Test
        @DisplayName("Positional: no exit when P&L within limits")
        void positionalNoExitWithinLimits() {
            TestableLongStraddleStrategy positional = createPositionalStrategy();
            positional.setEntryPremium(BigDecimal.valueOf(200));
            addPositionWithPnl(positional, BigDecimal.valueOf(50)); // 25% < 50% target

            assertThat(positional.callShouldExit(createSnapshot(22000))).isFalse();
        }
    }

    // ========================
    // ADJUSTMENT & MORPHS
    // ========================

    @Nested
    @DisplayName("Adjustment & Morphs")
    class AdjustmentAndMorphs {

        @Test
        @DisplayName("Adjust does nothing")
        void adjustIsNoOp() {
            scalpingStrategy.arm();
            scalpingStrategy.forceStatus(StrategyStatus.ACTIVE);

            scalpingStrategy.callAdjust(createSnapshot(22000));
            assertThat(scalpingStrategy.getLastAdjustmentType()).isNull();
        }

        @Test
        @DisplayName("No supported morphs")
        void noMorphs() {
            assertThat(scalpingStrategy.supportedMorphs()).isEmpty();
        }
    }

    // ========================
    // HELPERS
    // ========================

    private TestableLongStraddleStrategy createPositionalStrategy() {
        LongStraddleConfig positionalConfig = LongStraddleConfig.builder()
                .underlying("NIFTY")
                .lots(1)
                .strikeInterval(BigDecimal.valueOf(50))
                .autoEntry(true)
                .scalpingMode(false)
                .targetPercent(BigDecimal.valueOf(0.5))
                .stopLossMultiplier(BigDecimal.valueOf(0.5))
                .minDaysToExpiry(1)
                .entryStartTime(LocalTime.of(9, 20))
                .entryEndTime(LocalTime.of(15, 15))
                .build();

        return new TestableLongStraddleStrategy("STR-LST-POS", "Positional", positionalConfig);
    }

    private MarketSnapshot createSnapshot(double spotPrice) {
        return createSnapshot(spotPrice, null);
    }

    private MarketSnapshot createSnapshot(double spotPrice, BigDecimal atmIV) {
        return MarketSnapshot.builder()
                .spotPrice(BigDecimal.valueOf(spotPrice))
                .atmIV(atmIV)
                .timestamp(LocalDateTime.now())
                .build();
    }

    private void addPositionWithPnl(TestableLongStraddleStrategy strategy, BigDecimal unrealizedPnl) {
        Position position = Position.builder()
                .id("POS-" + System.nanoTime())
                .strategyId(STRATEGY_ID)
                .tradingSymbol("NIFTY22000CE")
                .quantity(75)
                .unrealizedPnl(unrealizedPnl)
                .realizedPnl(BigDecimal.ZERO)
                .lastUpdated(LocalDateTime.now())
                .build();
        strategy.addPosition(position);
    }

    // ========================
    // TESTABLE SUBCLASS
    // ========================

    /**
     * Exposes protected methods for testing and allows overriding time-dependent checks.
     */
    static class TestableLongStraddleStrategy extends LongStraddleStrategy {

        private Boolean withinEntryWindowOverride;
        private String lastAdjustmentType;

        TestableLongStraddleStrategy(String id, String name, LongStraddleConfig config) {
            super(id, name, config);
        }

        void forceWithinEntryWindow(boolean within) {
            this.withinEntryWindowOverride = within;
        }

        void forceStatus(StrategyStatus status) {
            this.status = status;
        }

        void forceEntryTime(LocalDateTime time) {
            this.entryTime = time;
        }

        String getLastAdjustmentType() {
            return lastAdjustmentType;
        }

        @Override
        protected boolean isWithinEntryWindow() {
            if (withinEntryWindowOverride != null) {
                return withinEntryWindowOverride;
            }
            return super.isWithinEntryWindow();
        }

        @Override
        protected void recordAdjustment(String adjustmentType) {
            this.lastAdjustmentType = adjustmentType;
            super.recordAdjustment(adjustmentType);
        }

        boolean callShouldEnter(MarketSnapshot snapshot) {
            return shouldEnter(snapshot);
        }

        List<OrderRequest> callBuildEntryOrders(MarketSnapshot snapshot) {
            return buildEntryOrders(snapshot);
        }

        boolean callShouldExit(MarketSnapshot snapshot) {
            return shouldExit(snapshot);
        }

        void callAdjust(MarketSnapshot snapshot) {
            adjust(snapshot);
        }
    }
}
