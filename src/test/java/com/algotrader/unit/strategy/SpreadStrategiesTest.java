package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Position;
import com.algotrader.oms.OrderRequest;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.MarketSnapshot;
import com.algotrader.strategy.impl.BearCallSpreadConfig;
import com.algotrader.strategy.impl.BearCallSpreadStrategy;
import com.algotrader.strategy.impl.BearPutSpreadConfig;
import com.algotrader.strategy.impl.BearPutSpreadStrategy;
import com.algotrader.strategy.impl.BullPutSpreadConfig;
import com.algotrader.strategy.impl.BullPutSpreadStrategy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the three spread strategies added in Phase 11.2:
 * BearPutSpreadStrategy, BullPutSpreadStrategy, BearCallSpreadStrategy.
 *
 * <p>Each strategy is tested for:
 * - Correct type and monitoring interval
 * - Leg construction (correct strikes, sides, quantity)
 * - Entry conditions (spot price checks, entry window)
 * - Exit conditions (target, stop loss)
 * - Adjustment (roll on P&L breach)
 * - Supported morphs
 */
class SpreadStrategiesTest {

    // ========================
    // BEAR PUT SPREAD
    // ========================

    @Nested
    @DisplayName("BearPutSpreadStrategy")
    class BearPutSpreadTests {

        private TestableBearPutSpread createStrategy() {
            BearPutSpreadConfig config = BearPutSpreadConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .buyOffset(BigDecimal.ZERO) // Buy PE at ATM
                    .sellOffset(BigDecimal.valueOf(-200)) // Sell PE at ATM - 200
                    .maxSpotForEntry(BigDecimal.valueOf(23000))
                    .rollThreshold(BigDecimal.valueOf(3000))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(2.0))
                    .minDaysToExpiry(2)
                    .entryStartTime(LocalTime.of(9, 20))
                    .entryEndTime(LocalTime.of(11, 0))
                    .build();
            return new TestableBearPutSpread("STR-BPS-001", "NIFTY-BearPut-Test", config);
        }

        @Test
        @DisplayName("Type is BEAR_PUT_SPREAD with 5-min interval")
        void identity() {
            TestableBearPutSpread strategy = createStrategy();
            assertThat(strategy.getType()).isEqualTo(StrategyType.BEAR_PUT_SPREAD);
            assertThat(strategy.getMonitoringInterval()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("Builds 2 legs: buy PE at ATM, sell PE at ATM-200")
        void legConstruction() {
            TestableBearPutSpread strategy = createStrategy();
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).hasSize(2);
            // Buy PE at ATM (higher strike)
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22000PE");
            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.BUY);
            // Sell PE at ATM - 200 (lower strike)
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY21800PE");
            assertThat(orders.get(1).getSide()).isEqualTo(OrderSide.SELL);
        }

        @Test
        @DisplayName("Entry rejected when spot > maxSpotForEntry")
        void noEntryWhenSpotTooHigh() {
            TestableBearPutSpread strategy = createStrategy();
            strategy.forceWithinEntryWindow(true);
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(24000)) // above 23000 max
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Entry allowed when spot <= maxSpotForEntry")
        void entryWhenSpotOK() {
            TestableBearPutSpread strategy = createStrategy();
            strategy.forceWithinEntryWindow(true);
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isTrue();
        }

        @Test
        @DisplayName("Exit on target profit")
        void exitOnTarget() {
            TestableBearPutSpread strategy = createStrategy();
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            addPositionWithPnl(strategy, BigDecimal.valueOf(100), BigDecimal.ZERO);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("Records ROLL_BUY_UP on P&L breach with buy leg present")
        void rollAdjustment() {
            TestableBearPutSpread strategy = createStrategy();
            strategy.arm();
            strategy.forceStatus(StrategyStatus.ACTIVE);

            // Add a buy leg (qty > 0, PE)
            Position buyLeg = Position.builder()
                    .id("POS-BUY")
                    .tradingSymbol("NIFTY22000PE")
                    .quantity(75)
                    .unrealizedPnl(BigDecimal.valueOf(-3500))
                    .realizedPnl(BigDecimal.ZERO)
                    .lastUpdated(LocalDateTime.now())
                    .build();
            strategy.addPosition(buyLeg);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22200))
                    .timestamp(LocalDateTime.now())
                    .build();

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isEqualTo("ROLL_BUY_UP");
        }

        @Test
        @DisplayName("Morphs to BULL_PUT_SPREAD and IRON_CONDOR")
        void supportedMorphs() {
            TestableBearPutSpread strategy = createStrategy();
            assertThat(strategy.supportedMorphs())
                    .containsExactlyInAnyOrder(StrategyType.BULL_PUT_SPREAD, StrategyType.IRON_CONDOR);
        }
    }

    // ========================
    // BULL PUT SPREAD
    // ========================

    @Nested
    @DisplayName("BullPutSpreadStrategy")
    class BullPutSpreadTests {

        private TestableBullPutSpread createStrategy() {
            BullPutSpreadConfig config = BullPutSpreadConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .sellOffset(BigDecimal.valueOf(-100)) // Sell PE at ATM - 100
                    .buyOffset(BigDecimal.valueOf(-300)) // Buy PE at ATM - 300
                    .minSpotForEntry(BigDecimal.valueOf(21000))
                    .rollThreshold(BigDecimal.valueOf(3000))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(2.0))
                    .minDaysToExpiry(2)
                    .entryStartTime(LocalTime.of(9, 20))
                    .entryEndTime(LocalTime.of(11, 0))
                    .build();
            return new TestableBullPutSpread("STR-BPS-002", "NIFTY-BullPut-Test", config);
        }

        @Test
        @DisplayName("Type is BULL_PUT_SPREAD with 5-min interval")
        void identity() {
            TestableBullPutSpread strategy = createStrategy();
            assertThat(strategy.getType()).isEqualTo(StrategyType.BULL_PUT_SPREAD);
            assertThat(strategy.getMonitoringInterval()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("Builds 2 legs: sell PE at ATM-100, buy PE at ATM-300")
        void legConstruction() {
            TestableBullPutSpread strategy = createStrategy();
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).hasSize(2);
            // Sell PE at ATM - 100 (higher strike)
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY21900PE");
            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.SELL);
            // Buy PE at ATM - 300 (lower strike)
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY21700PE");
            assertThat(orders.get(1).getSide()).isEqualTo(OrderSide.BUY);
        }

        @Test
        @DisplayName("Entry rejected when spot < minSpotForEntry")
        void noEntryWhenSpotTooLow() {
            TestableBullPutSpread strategy = createStrategy();
            strategy.forceWithinEntryWindow(true);
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(20000)) // below 21000 min
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Records ROLL_SELL_DOWN on P&L breach with sell leg present")
        void rollAdjustment() {
            TestableBullPutSpread strategy = createStrategy();
            strategy.arm();
            strategy.forceStatus(StrategyStatus.ACTIVE);

            Position sellLeg = Position.builder()
                    .id("POS-SELL")
                    .tradingSymbol("NIFTY21900PE")
                    .quantity(-75)
                    .unrealizedPnl(BigDecimal.valueOf(-3500))
                    .realizedPnl(BigDecimal.ZERO)
                    .lastUpdated(LocalDateTime.now())
                    .build();
            strategy.addPosition(sellLeg);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(21800))
                    .timestamp(LocalDateTime.now())
                    .build();

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isEqualTo("ROLL_SELL_DOWN");
        }

        @Test
        @DisplayName("Morphs to BEAR_PUT_SPREAD and IRON_CONDOR")
        void supportedMorphs() {
            TestableBullPutSpread strategy = createStrategy();
            assertThat(strategy.supportedMorphs())
                    .containsExactlyInAnyOrder(StrategyType.BEAR_PUT_SPREAD, StrategyType.IRON_CONDOR);
        }
    }

    // ========================
    // BEAR CALL SPREAD
    // ========================

    @Nested
    @DisplayName("BearCallSpreadStrategy")
    class BearCallSpreadTests {

        private TestableBearCallSpread createStrategy() {
            BearCallSpreadConfig config = BearCallSpreadConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .sellOffset(BigDecimal.valueOf(100)) // Sell CE at ATM + 100
                    .buyOffset(BigDecimal.valueOf(300)) // Buy CE at ATM + 300
                    .maxSpotForEntry(BigDecimal.valueOf(23000))
                    .rollThreshold(BigDecimal.valueOf(3000))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(2.0))
                    .minDaysToExpiry(2)
                    .entryStartTime(LocalTime.of(9, 20))
                    .entryEndTime(LocalTime.of(11, 0))
                    .build();
            return new TestableBearCallSpread("STR-BCS-001", "NIFTY-BearCall-Test", config);
        }

        @Test
        @DisplayName("Type is BEAR_CALL_SPREAD with 5-min interval")
        void identity() {
            TestableBearCallSpread strategy = createStrategy();
            assertThat(strategy.getType()).isEqualTo(StrategyType.BEAR_CALL_SPREAD);
            assertThat(strategy.getMonitoringInterval()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("Builds 2 legs: sell CE at ATM+100, buy CE at ATM+300")
        void legConstruction() {
            TestableBearCallSpread strategy = createStrategy();
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).hasSize(2);
            // Sell CE at ATM + 100 (lower strike)
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22100CE");
            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.SELL);
            // Buy CE at ATM + 300 (higher strike)
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY22300CE");
            assertThat(orders.get(1).getSide()).isEqualTo(OrderSide.BUY);
        }

        @Test
        @DisplayName("Entry rejected when spot > maxSpotForEntry")
        void noEntryWhenSpotTooHigh() {
            TestableBearCallSpread strategy = createStrategy();
            strategy.forceWithinEntryWindow(true);
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(24000))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Records ROLL_SELL_UP on P&L breach with sell leg present")
        void rollAdjustment() {
            TestableBearCallSpread strategy = createStrategy();
            strategy.arm();
            strategy.forceStatus(StrategyStatus.ACTIVE);

            Position sellLeg = Position.builder()
                    .id("POS-SELL")
                    .tradingSymbol("NIFTY22100CE")
                    .quantity(-75)
                    .unrealizedPnl(BigDecimal.valueOf(-3500))
                    .realizedPnl(BigDecimal.ZERO)
                    .lastUpdated(LocalDateTime.now())
                    .build();
            strategy.addPosition(sellLeg);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22300))
                    .timestamp(LocalDateTime.now())
                    .build();

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isEqualTo("ROLL_SELL_UP");
        }

        @Test
        @DisplayName("Morphs to BULL_CALL_SPREAD and IRON_CONDOR")
        void supportedMorphs() {
            TestableBearCallSpread strategy = createStrategy();
            assertThat(strategy.supportedMorphs())
                    .containsExactlyInAnyOrder(StrategyType.BULL_CALL_SPREAD, StrategyType.IRON_CONDOR);
        }
    }

    // ========================
    // HELPERS
    // ========================

    private void addPositionWithPnl(BaseStrategy strategy, BigDecimal unrealizedPnl, BigDecimal realizedPnl) {
        Position position = Position.builder()
                .id("POS-" + System.nanoTime())
                .tradingSymbol("NIFTY22000PE")
                .quantity(-75)
                .unrealizedPnl(unrealizedPnl)
                .realizedPnl(realizedPnl)
                .lastUpdated(LocalDateTime.now())
                .build();
        strategy.addPosition(position);
    }

    // ========================
    // TESTABLE SUBCLASSES
    // ========================

    static class TestableBearPutSpread extends BearPutSpreadStrategy {
        private Boolean withinEntryWindowOverride;
        private String lastAdjustmentType;

        TestableBearPutSpread(String id, String name, BearPutSpreadConfig config) {
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

    static class TestableBullPutSpread extends BullPutSpreadStrategy {
        private Boolean withinEntryWindowOverride;
        private String lastAdjustmentType;

        TestableBullPutSpread(String id, String name, BullPutSpreadConfig config) {
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

    static class TestableBearCallSpread extends BearCallSpreadStrategy {
        private Boolean withinEntryWindowOverride;
        private String lastAdjustmentType;

        TestableBearCallSpread(String id, String name, BearCallSpreadConfig config) {
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
