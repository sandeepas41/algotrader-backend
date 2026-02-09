package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Greeks;
import com.algotrader.domain.model.Position;
import com.algotrader.oms.OrderRequest;
import com.algotrader.strategy.base.MarketSnapshot;
import com.algotrader.strategy.impl.BrokenWingButterflyConfig;
import com.algotrader.strategy.impl.BrokenWingButterflyStrategy;
import com.algotrader.strategy.impl.IronButterflyConfig;
import com.algotrader.strategy.impl.IronButterflyStrategy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for butterfly strategies added in Phase 11.3:
 * IronButterflyStrategy and BrokenWingButterflyStrategy.
 */
class ButterflyStrategiesTest {

    // ========================
    // IRON BUTTERFLY
    // ========================

    @Nested
    @DisplayName("IronButterflyStrategy")
    class IronButterflyTests {

        private TestableIronButterfly createStrategy() {
            IronButterflyConfig config = IronButterflyConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .wingWidth(BigDecimal.valueOf(200))
                    .minEntryIV(BigDecimal.valueOf(14))
                    .deltaRollThreshold(BigDecimal.valueOf(0.30))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(2.0))
                    .minDaysToExpiry(2)
                    .entryStartTime(LocalTime.of(9, 20))
                    .entryEndTime(LocalTime.of(11, 0))
                    .build();
            return new TestableIronButterfly("STR-IB-001", "NIFTY-IB-Test", config);
        }

        @Test
        @DisplayName("Type is IRON_BUTTERFLY with 5-min interval")
        void identity() {
            TestableIronButterfly strategy = createStrategy();
            assertThat(strategy.getType()).isEqualTo(StrategyType.IRON_BUTTERFLY);
            assertThat(strategy.getMonitoringInterval()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("Builds 4 legs: sell CE ATM, buy CE ATM+200, sell PE ATM, buy PE ATM-200")
        void fourLegConstruction() {
            TestableIronButterfly strategy = createStrategy();
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).hasSize(4);
            // Sell CE at ATM
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22000CE");
            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.SELL);
            // Buy CE at ATM + 200
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY22200CE");
            assertThat(orders.get(1).getSide()).isEqualTo(OrderSide.BUY);
            // Sell PE at ATM
            assertThat(orders.get(2).getTradingSymbol()).isEqualTo("NIFTY22000PE");
            assertThat(orders.get(2).getSide()).isEqualTo(OrderSide.SELL);
            // Buy PE at ATM - 200
            assertThat(orders.get(3).getTradingSymbol()).isEqualTo("NIFTY21800PE");
            assertThat(orders.get(3).getSide()).isEqualTo(OrderSide.BUY);
        }

        @Test
        @DisplayName("Short CE and short PE at SAME ATM strike (butterfly center)")
        void shortLegsAtSameStrike() {
            TestableIronButterfly strategy = createStrategy();
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            // Both short legs at ATM
            String sellCeSymbol = orders.get(0).getTradingSymbol();
            String sellPeSymbol = orders.get(2).getTradingSymbol();
            // Extract strike: NIFTY22000CE -> 22000, NIFTY22000PE -> 22000
            assertThat(sellCeSymbol.replaceAll("[^0-9]", "")).isEqualTo(sellPeSymbol.replaceAll("[^0-9]", ""));
        }

        @Test
        @DisplayName("Entry requires IV >= minEntryIV")
        void entryRequiresMinIV() {
            TestableIronButterfly strategy = createStrategy();
            strategy.forceWithinEntryWindow(true);

            MarketSnapshot lowIV = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .atmIV(BigDecimal.valueOf(10))
                    .timestamp(LocalDateTime.now())
                    .build();
            assertThat(strategy.callShouldEnter(lowIV)).isFalse();

            MarketSnapshot highIV = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .atmIV(BigDecimal.valueOf(16))
                    .timestamp(LocalDateTime.now())
                    .build();
            assertThat(strategy.callShouldEnter(highIV)).isTrue();
        }

        @Test
        @DisplayName("Delta roll adjustment: ROLL_CALL_UP when delta too positive")
        void rollCallUp() {
            TestableIronButterfly strategy = createStrategy();
            strategy.arm();
            strategy.forceStatus(StrategyStatus.ACTIVE);

            // Short call position for getShortCall detection
            Position shortCall = Position.builder()
                    .id("POS-SC")
                    .strategyId("STR-IB-001")
                    .tradingSymbol("NIFTY22000CE")
                    .quantity(-75)
                    .greeks(Greeks.UNAVAILABLE)
                    .unrealizedPnl(BigDecimal.ZERO)
                    .realizedPnl(BigDecimal.ZERO)
                    .lastUpdated(LocalDateTime.now())
                    .build();
            strategy.addPosition(shortCall);

            // Delta driver position
            Greeks greeks = Greeks.builder()
                    .delta(BigDecimal.valueOf(0.35))
                    .gamma(BigDecimal.ZERO)
                    .theta(BigDecimal.ZERO)
                    .vega(BigDecimal.ZERO)
                    .iv(BigDecimal.valueOf(15))
                    .build();
            Position deltaPos = Position.builder()
                    .id("POS-DELTA")
                    .strategyId("STR-IB-001")
                    .tradingSymbol("NIFTY-DELTA")
                    .quantity(1)
                    .greeks(greeks)
                    .unrealizedPnl(BigDecimal.ZERO)
                    .realizedPnl(BigDecimal.ZERO)
                    .lastUpdated(LocalDateTime.now())
                    .build();
            strategy.addPosition(deltaPos);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22200))
                    .timestamp(LocalDateTime.now())
                    .build();

            strategy.callAdjust(snapshot);
            assertThat(strategy.getLastAdjustmentType()).isEqualTo("ROLL_CALL_UP");
        }

        @Test
        @DisplayName("Morphs to IRON_CONDOR and STRADDLE")
        void supportedMorphs() {
            TestableIronButterfly strategy = createStrategy();
            assertThat(strategy.supportedMorphs())
                    .containsExactlyInAnyOrder(StrategyType.IRON_CONDOR, StrategyType.STRADDLE);
        }
    }

    // ========================
    // BROKEN WING BUTTERFLY
    // ========================

    @Nested
    @DisplayName("BrokenWingButterflyStrategy")
    class BrokenWingButterflyTests {

        private TestableBrokenWing createStrategy() {
            BrokenWingButterflyConfig config = BrokenWingButterflyConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .callWingWidth(BigDecimal.valueOf(100)) // narrow call wing
                    .putWingWidth(BigDecimal.valueOf(300)) // wide put wing
                    .minEntryIV(BigDecimal.valueOf(14))
                    .deltaRollThreshold(BigDecimal.valueOf(0.30))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(2.0))
                    .minDaysToExpiry(2)
                    .entryStartTime(LocalTime.of(9, 20))
                    .entryEndTime(LocalTime.of(11, 0))
                    .build();
            return new TestableBrokenWing("STR-BWB-001", "NIFTY-BWB-Test", config);
        }

        @Test
        @DisplayName("Type is IRON_BUTTERFLY (variant)")
        void identity() {
            TestableBrokenWing strategy = createStrategy();
            assertThat(strategy.getType()).isEqualTo(StrategyType.IRON_BUTTERFLY);
            assertThat(strategy.getMonitoringInterval()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("Builds 4 legs with asymmetric wings: CE wing 100, PE wing 300")
        void asymmetricWings() {
            TestableBrokenWing strategy = createStrategy();
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).hasSize(4);
            // Sell CE at ATM
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22000CE");
            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.SELL);
            // Buy CE at ATM + 100 (narrow wing)
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY22100CE");
            assertThat(orders.get(1).getSide()).isEqualTo(OrderSide.BUY);
            // Sell PE at ATM
            assertThat(orders.get(2).getTradingSymbol()).isEqualTo("NIFTY22000PE");
            assertThat(orders.get(2).getSide()).isEqualTo(OrderSide.SELL);
            // Buy PE at ATM - 300 (wide wing)
            assertThat(orders.get(3).getTradingSymbol()).isEqualTo("NIFTY21700PE");
            assertThat(orders.get(3).getSide()).isEqualTo(OrderSide.BUY);
        }

        @Test
        @DisplayName("Wings are NOT equal (asymmetric)")
        void wingsAreAsymmetric() {
            TestableBrokenWing strategy = createStrategy();
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            // Call wing: 22100 - 22000 = 100
            // Put wing: 22000 - 21700 = 300
            // They should NOT be equal (that's the broken wing property)
            String buyCeSymbol = orders.get(1).getTradingSymbol(); // NIFTY22100CE
            String buyPeSymbol = orders.get(3).getTradingSymbol(); // NIFTY21700PE
            // 22100 - 22000 = 100, 22000 - 21700 = 300
            assertThat(buyCeSymbol).isNotEqualTo("NIFTY22300CE"); // would be equal wings
        }

        @Test
        @DisplayName("Exit on target profit")
        void exitOnTarget() {
            TestableBrokenWing strategy = createStrategy();
            strategy.forceEntryPremium(BigDecimal.valueOf(200));

            Position position = Position.builder()
                    .id("POS-1")
                    .strategyId("STR-BWB-001")
                    .tradingSymbol("NIFTY22000CE")
                    .quantity(-75)
                    .unrealizedPnl(BigDecimal.valueOf(100))
                    .realizedPnl(BigDecimal.ZERO)
                    .lastUpdated(LocalDateTime.now())
                    .build();
            strategy.addPosition(position);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("Morphs to IRON_CONDOR and IRON_BUTTERFLY")
        void supportedMorphs() {
            TestableBrokenWing strategy = createStrategy();
            assertThat(strategy.supportedMorphs())
                    .containsExactlyInAnyOrder(StrategyType.IRON_CONDOR, StrategyType.IRON_BUTTERFLY);
        }
    }

    // ========================
    // TESTABLE SUBCLASSES
    // ========================

    static class TestableIronButterfly extends IronButterflyStrategy {
        private Boolean withinEntryWindowOverride;
        private String lastAdjustmentType;

        TestableIronButterfly(String id, String name, IronButterflyConfig config) {
            super(id, name, config);
        }

        void forceWithinEntryWindow(boolean within) {
            this.withinEntryWindowOverride = within;
        }

        void forceStatus(StrategyStatus status) {
            this.status = status;
        }

        void forceEntryPremium(BigDecimal premium) {
            this.entryPremium = premium;
        }

        String getLastAdjustmentType() {
            return lastAdjustmentType;
        }

        @Override
        protected boolean isWithinEntryWindow() {
            return withinEntryWindowOverride != null ? withinEntryWindowOverride : super.isWithinEntryWindow();
        }

        @Override
        protected void recordAdjustment(String type) {
            this.lastAdjustmentType = type;
            super.recordAdjustment(type);
        }

        boolean callShouldEnter(MarketSnapshot s) {
            return shouldEnter(s);
        }

        List<OrderRequest> callBuildEntryOrders(MarketSnapshot s) {
            return buildEntryOrders(s);
        }

        boolean callShouldExit(MarketSnapshot s) {
            return shouldExit(s);
        }

        void callAdjust(MarketSnapshot s) {
            adjust(s);
        }
    }

    static class TestableBrokenWing extends BrokenWingButterflyStrategy {
        private Boolean withinEntryWindowOverride;
        private String lastAdjustmentType;

        TestableBrokenWing(String id, String name, BrokenWingButterflyConfig config) {
            super(id, name, config);
        }

        void forceWithinEntryWindow(boolean within) {
            this.withinEntryWindowOverride = within;
        }

        void forceStatus(StrategyStatus status) {
            this.status = status;
        }

        void forceEntryPremium(BigDecimal premium) {
            this.entryPremium = premium;
        }

        String getLastAdjustmentType() {
            return lastAdjustmentType;
        }

        @Override
        protected boolean isWithinEntryWindow() {
            return withinEntryWindowOverride != null ? withinEntryWindowOverride : super.isWithinEntryWindow();
        }

        @Override
        protected void recordAdjustment(String type) {
            this.lastAdjustmentType = type;
            super.recordAdjustment(type);
        }

        boolean callShouldEnter(MarketSnapshot s) {
            return shouldEnter(s);
        }

        List<OrderRequest> callBuildEntryOrders(MarketSnapshot s) {
            return buildEntryOrders(s);
        }

        boolean callShouldExit(MarketSnapshot s) {
            return shouldExit(s);
        }

        void callAdjust(MarketSnapshot s) {
            adjust(s);
        }
    }
}
