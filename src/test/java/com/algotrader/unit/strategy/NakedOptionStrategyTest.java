package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Position;
import com.algotrader.oms.OrderRequest;
import com.algotrader.strategy.base.MarketSnapshot;
import com.algotrader.strategy.impl.NakedOptionConfig;
import com.algotrader.strategy.impl.NakedOptionStrategy;
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
 * Unit tests for NakedOptionStrategy covering all 4 types (CE_BUY, CE_SELL, PE_BUY, PE_SELL),
 * both scalping and positional modes, strike resolution (explicit, ATM, ATM+offset),
 * entry conditions, exit conditions, no-op adjustment, and no morphing.
 */
class NakedOptionStrategyTest {

    private static final String STRATEGY_ID = "STR-NAK-001";
    private static final String STRATEGY_NAME = "NIFTY-CeBuy-Test";

    // ========================
    // CE_BUY TESTS (default config)
    // ========================

    @Nested
    @DisplayName("CE_BUY - Identity & Type")
    class CeBuyIdentity {

        private TestableNakedOptionStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = createScalpingStrategy(StrategyType.CE_BUY);
        }

        @Test
        @DisplayName("Type is CE_BUY")
        void typeIsCeBuy() {
            assertThat(strategy.getType()).isEqualTo(StrategyType.CE_BUY);
        }

        @Test
        @DisplayName("Scalping mode: tick-level monitoring")
        void scalpingMonitoringInterval() {
            assertThat(strategy.getMonitoringInterval()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("Scalping mode: 2s stale data threshold")
        void scalpingStaleDataThreshold() {
            assertThat(strategy.getStaleDataThreshold()).isEqualTo(Duration.ofSeconds(2));
        }
    }

    @Nested
    @DisplayName("CE_SELL - Identity & Type")
    class CeSellIdentity {

        @Test
        @DisplayName("Type is CE_SELL")
        void typeIsCeSell() {
            TestableNakedOptionStrategy strategy = createScalpingStrategy(StrategyType.CE_SELL);
            assertThat(strategy.getType()).isEqualTo(StrategyType.CE_SELL);
        }
    }

    @Nested
    @DisplayName("PE_BUY - Identity & Type")
    class PeBuyIdentity {

        @Test
        @DisplayName("Type is PE_BUY")
        void typeIsPeBuy() {
            TestableNakedOptionStrategy strategy = createScalpingStrategy(StrategyType.PE_BUY);
            assertThat(strategy.getType()).isEqualTo(StrategyType.PE_BUY);
        }
    }

    @Nested
    @DisplayName("PE_SELL - Identity & Type")
    class PeSellIdentity {

        @Test
        @DisplayName("Type is PE_SELL")
        void typeIsPeSell() {
            TestableNakedOptionStrategy strategy = createScalpingStrategy(StrategyType.PE_SELL);
            assertThat(strategy.getType()).isEqualTo(StrategyType.PE_SELL);
        }
    }

    // ========================
    // POSITIONAL MODE
    // ========================

    @Nested
    @DisplayName("Positional Mode")
    class PositionalMode {

        private TestableNakedOptionStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = createPositionalStrategy(StrategyType.CE_BUY);
        }

        @Test
        @DisplayName("Positional mode: 5-min monitoring interval")
        void positionalMonitoringInterval() {
            assertThat(strategy.getMonitoringInterval()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("Positional mode: 5s stale data threshold (default)")
        void positionalStaleDataThreshold() {
            assertThat(strategy.getStaleDataThreshold()).isEqualTo(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("Positional exit: target profit reached")
        void positionalExitOnTarget() {
            strategy.setEntryPremium(BigDecimal.valueOf(100));
            addPositionWithPnl(strategy, BigDecimal.valueOf(50)); // 50% of entry premium
            strategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = createSnapshot(22000);
            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("Positional exit: stop loss hit")
        void positionalExitOnStopLoss() {
            strategy.setEntryPremium(BigDecimal.valueOf(100));
            // stopLossMultiplier = 1.5 -> stop at -150
            addPositionWithPnl(strategy, BigDecimal.valueOf(-150));

            MarketSnapshot snapshot = createSnapshot(22000);
            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("Positional: no exit when P&L within limits")
        void positionalNoExitWithinLimits() {
            strategy.setEntryPremium(BigDecimal.valueOf(100));
            addPositionWithPnl(strategy, BigDecimal.valueOf(30)); // 30% < 50% target

            MarketSnapshot snapshot = createSnapshot(22000);
            assertThat(strategy.callShouldExit(snapshot)).isFalse();
        }
    }

    // ========================
    // ENTRY CONDITIONS
    // ========================

    @Nested
    @DisplayName("Entry Conditions")
    class EntryConditions {

        @Test
        @DisplayName("Returns true when autoEntry enabled and within window")
        void entryWhenAutoEntryAndWithinWindow() {
            TestableNakedOptionStrategy strategy = createScalpingStrategy(StrategyType.CE_BUY);
            strategy.forceWithinEntryWindow(true);

            assertThat(strategy.callShouldEnter(createSnapshot(22000))).isTrue();
        }

        @Test
        @DisplayName("Returns false when autoEntry disabled")
        void noEntryWhenAutoEntryDisabled() {
            NakedOptionConfig config = NakedOptionConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .autoEntry(false)
                    .scalpingMode(true)
                    .build();

            TestableNakedOptionStrategy strategy =
                    new TestableNakedOptionStrategy("STR-NAK-MAN", "Manual", StrategyType.CE_BUY, config);
            strategy.forceWithinEntryWindow(true);

            assertThat(strategy.callShouldEnter(createSnapshot(22000))).isFalse();
        }

        @Test
        @DisplayName("Returns false when outside entry window")
        void noEntryOutsideWindow() {
            TestableNakedOptionStrategy strategy = createScalpingStrategy(StrategyType.CE_BUY);
            strategy.forceWithinEntryWindow(false);

            assertThat(strategy.callShouldEnter(createSnapshot(22000))).isFalse();
        }
    }

    // ========================
    // ENTRY ORDER CONSTRUCTION (ALL 4 TYPES)
    // ========================

    @Nested
    @DisplayName("Entry Order Construction")
    class EntryOrderConstruction {

        @Test
        @DisplayName("CE_BUY: 1 leg BUY CE")
        void ceBuyOrder() {
            TestableNakedOptionStrategy strategy = createScalpingStrategy(StrategyType.CE_BUY);
            List<OrderRequest> orders = strategy.callBuildEntryOrders(createSnapshot(22000));

            assertThat(orders).hasSize(1);
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22000CE");
            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.BUY);
        }

        @Test
        @DisplayName("CE_SELL: 1 leg SELL CE")
        void ceSellOrder() {
            TestableNakedOptionStrategy strategy = createScalpingStrategy(StrategyType.CE_SELL);
            List<OrderRequest> orders = strategy.callBuildEntryOrders(createSnapshot(22000));

            assertThat(orders).hasSize(1);
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22000CE");
            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.SELL);
        }

        @Test
        @DisplayName("PE_BUY: 1 leg BUY PE")
        void peBuyOrder() {
            TestableNakedOptionStrategy strategy = createScalpingStrategy(StrategyType.PE_BUY);
            List<OrderRequest> orders = strategy.callBuildEntryOrders(createSnapshot(22000));

            assertThat(orders).hasSize(1);
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22000PE");
            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.BUY);
        }

        @Test
        @DisplayName("PE_SELL: 1 leg SELL PE")
        void peSellOrder() {
            TestableNakedOptionStrategy strategy = createScalpingStrategy(StrategyType.PE_SELL);
            List<OrderRequest> orders = strategy.callBuildEntryOrders(createSnapshot(22000));

            assertThat(orders).hasSize(1);
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22000PE");
            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.SELL);
        }

        @Test
        @DisplayName("All legs are MARKET orders with quantity 1")
        void marketOrdersQuantity1() {
            TestableNakedOptionStrategy strategy = createScalpingStrategy(StrategyType.CE_BUY);
            List<OrderRequest> orders = strategy.callBuildEntryOrders(createSnapshot(22000));

            assertThat(orders.get(0).getQuantity()).isEqualTo(1);
            assertThat(orders.get(0).getType()).isEqualTo(com.algotrader.domain.enums.OrderType.MARKET);
        }
    }

    // ========================
    // STRIKE RESOLUTION
    // ========================

    @Nested
    @DisplayName("Strike Resolution")
    class StrikeResolution {

        @Test
        @DisplayName("Uses explicit strike when configured")
        void usesExplicitStrike() {
            NakedOptionConfig config = NakedOptionConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .autoEntry(true)
                    .scalpingMode(true)
                    .strike(BigDecimal.valueOf(22500))
                    .build();

            TestableNakedOptionStrategy strategy =
                    new TestableNakedOptionStrategy("STR-NAK-EX", "Explicit", StrategyType.CE_BUY, config);

            List<OrderRequest> orders = strategy.callBuildEntryOrders(createSnapshot(22000));
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22500CE");
        }

        @Test
        @DisplayName("Falls back to ATM when strike is null and offset is 0")
        void fallsBackToATM() {
            TestableNakedOptionStrategy strategy = createScalpingStrategy(StrategyType.CE_BUY);
            List<OrderRequest> orders = strategy.callBuildEntryOrders(createSnapshot(22035));

            // ATM rounded to 22050
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22050CE");
        }

        @Test
        @DisplayName("CE with positive offset = OTM (higher strike)")
        void cePositiveOffset() {
            NakedOptionConfig config = NakedOptionConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .autoEntry(true)
                    .scalpingMode(true)
                    .strikeOffset(2)
                    .build();

            TestableNakedOptionStrategy strategy =
                    new TestableNakedOptionStrategy("STR-NAK-OTM", "OTM", StrategyType.CE_BUY, config);

            List<OrderRequest> orders = strategy.callBuildEntryOrders(createSnapshot(22000));
            // ATM=22000, offset=+2*50=+100 -> 22100
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22100CE");
        }

        @Test
        @DisplayName("PE with positive offset = OTM (lower strike)")
        void pePositiveOffset() {
            NakedOptionConfig config = NakedOptionConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .autoEntry(true)
                    .scalpingMode(true)
                    .strikeOffset(2)
                    .build();

            TestableNakedOptionStrategy strategy =
                    new TestableNakedOptionStrategy("STR-NAK-OTM-PE", "OTM-PE", StrategyType.PE_BUY, config);

            List<OrderRequest> orders = strategy.callBuildEntryOrders(createSnapshot(22000));
            // ATM=22000, PE offset=+2 means OTM = lower strike -> 22000 - 100 = 21900
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY21900PE");
        }
    }

    // ========================
    // EXIT CONDITIONS (SCALPING MODE)
    // ========================

    @Nested
    @DisplayName("Scalping Exit Conditions")
    class ScalpingExitConditions {

        private TestableNakedOptionStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = createScalpingStrategy(StrategyType.CE_BUY);
        }

        @Test
        @DisplayName("No exit when no positions")
        void noExitWhenNoPositions() {
            assertThat(strategy.callShouldExit(createSnapshot(22000))).isFalse();
        }

        @Test
        @DisplayName("Exit on target: P&L >= 20 points")
        void exitOnTargetPoints() {
            addPositionWithPnl(strategy, BigDecimal.valueOf(20));
            assertThat(strategy.callShouldExit(createSnapshot(22000))).isTrue();
        }

        @Test
        @DisplayName("No exit when P&L below target")
        void noExitBelowTarget() {
            addPositionWithPnl(strategy, BigDecimal.valueOf(15));
            assertThat(strategy.callShouldExit(createSnapshot(22000))).isFalse();
        }

        @Test
        @DisplayName("Exit on stop loss: P&L <= -10 points")
        void exitOnStopLoss() {
            addPositionWithPnl(strategy, BigDecimal.valueOf(-10));
            assertThat(strategy.callShouldExit(createSnapshot(22000))).isTrue();
        }

        @Test
        @DisplayName("No exit when loss within SL limit")
        void noExitWithinSL() {
            addPositionWithPnl(strategy, BigDecimal.valueOf(-8));
            assertThat(strategy.callShouldExit(createSnapshot(22000))).isFalse();
        }

        @Test
        @DisplayName("Exit on max hold duration exceeded")
        void exitOnMaxHoldDuration() {
            strategy.forceEntryTime(LocalDateTime.now().minusMinutes(11));
            addPositionWithPnl(strategy, BigDecimal.valueOf(5));
            assertThat(strategy.callShouldExit(createSnapshot(22000))).isTrue();
        }

        @Test
        @DisplayName("No exit when within hold duration")
        void noExitWithinHoldDuration() {
            strategy.forceEntryTime(LocalDateTime.now().minusMinutes(5));
            addPositionWithPnl(strategy, BigDecimal.valueOf(5));
            assertThat(strategy.callShouldExit(createSnapshot(22000))).isFalse();
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
            TestableNakedOptionStrategy strategy = createScalpingStrategy(StrategyType.CE_BUY);
            strategy.arm();
            strategy.forceStatus(StrategyStatus.ACTIVE);

            strategy.callAdjust(createSnapshot(22000));
            assertThat(strategy.getLastAdjustmentType()).isNull();
        }

        @Test
        @DisplayName("No supported morphs")
        void noMorphs() {
            TestableNakedOptionStrategy strategy = createScalpingStrategy(StrategyType.CE_BUY);
            assertThat(strategy.supportedMorphs()).isEmpty();
        }
    }

    // ========================
    // HELPERS
    // ========================

    private TestableNakedOptionStrategy createScalpingStrategy(StrategyType type) {
        NakedOptionConfig config = NakedOptionConfig.builder()
                .underlying("NIFTY")
                .lots(1)
                .strikeInterval(BigDecimal.valueOf(50))
                .autoEntry(true)
                .scalpingMode(true)
                .targetPoints(BigDecimal.valueOf(20))
                .stopLossPoints(BigDecimal.valueOf(10))
                .maxHoldDuration(Duration.ofMinutes(10))
                .entryStartTime(LocalTime.of(9, 20))
                .entryEndTime(LocalTime.of(15, 15))
                .build();

        return new TestableNakedOptionStrategy(STRATEGY_ID, STRATEGY_NAME, type, config);
    }

    private TestableNakedOptionStrategy createPositionalStrategy(StrategyType type) {
        NakedOptionConfig config = NakedOptionConfig.builder()
                .underlying("NIFTY")
                .lots(1)
                .strikeInterval(BigDecimal.valueOf(50))
                .autoEntry(true)
                .scalpingMode(false)
                .targetPercent(BigDecimal.valueOf(0.5))
                .stopLossMultiplier(BigDecimal.valueOf(1.5))
                .minDaysToExpiry(1)
                .entryStartTime(LocalTime.of(9, 20))
                .entryEndTime(LocalTime.of(15, 15))
                .build();

        return new TestableNakedOptionStrategy("STR-NAK-POS", "Positional", type, config);
    }

    private MarketSnapshot createSnapshot(double spotPrice) {
        return MarketSnapshot.builder()
                .spotPrice(BigDecimal.valueOf(spotPrice))
                .timestamp(LocalDateTime.now())
                .build();
    }

    private void addPositionWithPnl(TestableNakedOptionStrategy strategy, BigDecimal unrealizedPnl) {
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
    static class TestableNakedOptionStrategy extends NakedOptionStrategy {

        private Boolean withinEntryWindowOverride;
        private String lastAdjustmentType;

        TestableNakedOptionStrategy(String id, String name, StrategyType type, NakedOptionConfig config) {
            super(id, name, type, config);
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
