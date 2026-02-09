package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Position;
import com.algotrader.oms.OrderRequest;
import com.algotrader.strategy.base.MarketSnapshot;
import com.algotrader.strategy.impl.ScalpingConfig;
import com.algotrader.strategy.impl.ScalpingStrategy;
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
 * Unit tests for ScalpingStrategy covering tick-level monitoring, 2s stale data threshold,
 * auto-entry control, single-leg construction, point-based exits (target/SL/max hold),
 * no-op adjustment, and no morphing.
 */
class ScalpingStrategyTest {

    private static final String STRATEGY_ID = "STR-SCA-001";
    private static final String STRATEGY_NAME = "NIFTY-Scalp-Test";

    private ScalpingConfig config;
    private TestableScalpingStrategy strategy;

    @BeforeEach
    void setUp() {
        config = ScalpingConfig.builder()
                .underlying("NIFTY")
                .lots(1)
                .strikeInterval(BigDecimal.valueOf(50))
                .autoEntry(true)
                .optionType("CE")
                .strike(BigDecimal.valueOf(22000))
                .side(OrderSide.BUY)
                .targetPoints(BigDecimal.valueOf(20))
                .stopLossPoints(BigDecimal.valueOf(10))
                .maxHoldDuration(Duration.ofMinutes(10))
                .entryStartTime(LocalTime.of(9, 20))
                .entryEndTime(LocalTime.of(15, 15))
                .build();

        strategy = new TestableScalpingStrategy(STRATEGY_ID, STRATEGY_NAME, config);
    }

    // ========================
    // IDENTITY & TYPE
    // ========================

    @Nested
    @DisplayName("Identity & Type")
    class IdentityAndType {

        @Test
        @DisplayName("Type is SCALPING")
        void typeIsScalping() {
            assertThat(strategy.getType()).isEqualTo(StrategyType.SCALPING);
        }

        @Test
        @DisplayName("Monitoring interval is Duration.ZERO (every tick)")
        void monitoringIntervalEveryTick() {
            assertThat(strategy.getMonitoringInterval()).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("Stale data threshold is 2 seconds (tighter than 5s default)")
        void staleDataThreshold2Seconds() {
            assertThat(strategy.getStaleDataThreshold()).isEqualTo(Duration.ofSeconds(2));
        }

        @Test
        @DisplayName("Underlying from config")
        void underlying() {
            assertThat(strategy.getUnderlying()).isEqualTo("NIFTY");
        }
    }

    // ========================
    // ENTRY CONDITIONS
    // ========================

    @Nested
    @DisplayName("Entry Conditions (shouldEnter)")
    class EntryConditions {

        @Test
        @DisplayName("Returns true when autoEntry enabled and within window")
        void entryWhenAutoEntryAndWithinWindow() {
            strategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isTrue();
        }

        @Test
        @DisplayName("Returns false when autoEntry disabled")
        void noEntryWhenAutoEntryDisabled() {
            ScalpingConfig manualConfig = ScalpingConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .autoEntry(false)
                    .optionType("CE")
                    .side(OrderSide.BUY)
                    .build();

            TestableScalpingStrategy manualStrategy =
                    new TestableScalpingStrategy("STR-SCA-MAN", "Manual", manualConfig);
            manualStrategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(manualStrategy.callShouldEnter(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Returns false when outside entry window")
        void noEntryOutsideWindow() {
            strategy.forceWithinEntryWindow(false);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isFalse();
        }
    }

    // ========================
    // SINGLE-LEG CONSTRUCTION
    // ========================

    @Nested
    @DisplayName("Entry Order Construction (1 leg)")
    class EntryOrderConstruction {

        @Test
        @DisplayName("Produces exactly 1 leg")
        void oneLeg() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).hasSize(1);
        }

        @Test
        @DisplayName("Uses configured strike and option type")
        void usesConfiguredStrike() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22000CE");
            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.BUY);
        }

        @Test
        @DisplayName("Falls back to ATM when strike is null")
        void fallsBackToATM() {
            ScalpingConfig noStrikeConfig = ScalpingConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .autoEntry(true)
                    .optionType("PE")
                    .strike(null)
                    .side(OrderSide.SELL)
                    .build();

            TestableScalpingStrategy noStrikeStrategy =
                    new TestableScalpingStrategy("STR-SCA-NS", "NoStrike", noStrikeConfig);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22035))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = noStrikeStrategy.callBuildEntryOrders(snapshot);

            // ATM rounded to 22050
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22050PE");
            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.SELL);
        }

        @Test
        @DisplayName("Defaults to CE when optionType is null")
        void defaultsCEWhenOptionTypeNull() {
            ScalpingConfig noTypeConfig = ScalpingConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .autoEntry(true)
                    .optionType(null)
                    .strike(BigDecimal.valueOf(22000))
                    .side(OrderSide.BUY)
                    .build();

            TestableScalpingStrategy noTypeStrategy =
                    new TestableScalpingStrategy("STR-SCA-NT", "NoType", noTypeConfig);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = noTypeStrategy.callBuildEntryOrders(snapshot);

            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22000CE");
        }

        @Test
        @DisplayName("All legs are MARKET orders with quantity 1")
        void marketOrdersQuantity1() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders.get(0).getQuantity()).isEqualTo(1);
            assertThat(orders.get(0).getType()).isEqualTo(com.algotrader.domain.enums.OrderType.MARKET);
        }
    }

    // ========================
    // EXIT CONDITIONS (POINT-BASED)
    // ========================

    @Nested
    @DisplayName("Exit Conditions (point-based)")
    class ExitConditions {

        private MarketSnapshot snapshot;

        @BeforeEach
        void setUp() {
            snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();
        }

        @Test
        @DisplayName("No exit when no positions")
        void noExitWhenNoPositions() {
            assertThat(strategy.callShouldExit(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Exit on target: P&L >= 20 points")
        void exitOnTargetPoints() {
            addPositionWithPnl(BigDecimal.valueOf(20));

            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("No exit when P&L below target")
        void noExitBelowTarget() {
            addPositionWithPnl(BigDecimal.valueOf(15));

            assertThat(strategy.callShouldExit(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Exit on stop loss: P&L <= -10 points")
        void exitOnStopLoss() {
            addPositionWithPnl(BigDecimal.valueOf(-10));

            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("No exit when loss within SL limit")
        void noExitWithinSL() {
            addPositionWithPnl(BigDecimal.valueOf(-8));

            assertThat(strategy.callShouldExit(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Exit on max hold duration exceeded")
        void exitOnMaxHoldDuration() {
            // Entry was 11 minutes ago, maxHoldDuration = 10 minutes
            strategy.forceEntryTime(LocalDateTime.now().minusMinutes(11));
            addPositionWithPnl(BigDecimal.valueOf(5));

            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("No exit when within hold duration")
        void noExitWithinHoldDuration() {
            // Entry was 5 minutes ago, maxHoldDuration = 10 minutes
            strategy.forceEntryTime(LocalDateTime.now().minusMinutes(5));
            addPositionWithPnl(BigDecimal.valueOf(5));

            assertThat(strategy.callShouldExit(snapshot)).isFalse();
        }

        @Test
        @DisplayName("No exit when maxHoldDuration is null")
        void noExitWhenMaxHoldNull() {
            ScalpingConfig noHoldConfig = ScalpingConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .autoEntry(true)
                    .optionType("CE")
                    .side(OrderSide.BUY)
                    .targetPoints(BigDecimal.valueOf(20))
                    .stopLossPoints(BigDecimal.valueOf(10))
                    .maxHoldDuration(null)
                    .build();

            TestableScalpingStrategy noHoldStrategy =
                    new TestableScalpingStrategy("STR-SCA-NH", "NoHold", noHoldConfig);
            noHoldStrategy.forceEntryTime(LocalDateTime.now().minusHours(1));

            Position position = Position.builder()
                    .id("POS-NH")
                    .strategyId("STR-SCA-NH")
                    .tradingSymbol("NIFTY22000CE")
                    .quantity(75)
                    .unrealizedPnl(BigDecimal.valueOf(5))
                    .realizedPnl(BigDecimal.ZERO)
                    .lastUpdated(LocalDateTime.now())
                    .build();
            noHoldStrategy.addPosition(position);

            assertThat(noHoldStrategy.callShouldExit(snapshot)).isFalse();
        }
    }

    // ========================
    // ADJUSTMENT (NO-OP)
    // ========================

    @Nested
    @DisplayName("Adjustment (no-op)")
    class Adjustment {

        @Test
        @DisplayName("Adjust does nothing (scalps exit, they don't adjust)")
        void adjustIsNoOp() {
            strategy.arm();
            strategy.forceStatus(StrategyStatus.ACTIVE);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            // Should not throw, and should not record any adjustment
            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isNull();
        }
    }

    // ========================
    // SUPPORTED MORPHS
    // ========================

    @Nested
    @DisplayName("Supported Morphs")
    class SupportedMorphs {

        @Test
        @DisplayName("No supported morphs (scalps don't morph)")
        void noMorphs() {
            assertThat(strategy.supportedMorphs()).isEmpty();
        }
    }

    // ========================
    // HELPERS
    // ========================

    private void addPositionWithPnl(BigDecimal unrealizedPnl) {
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
     * Exposes protected methods and tracks state for assertions.
     */
    static class TestableScalpingStrategy extends ScalpingStrategy {

        private Boolean withinEntryWindowOverride;
        private String lastAdjustmentType;

        TestableScalpingStrategy(String id, String name, ScalpingConfig config) {
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
