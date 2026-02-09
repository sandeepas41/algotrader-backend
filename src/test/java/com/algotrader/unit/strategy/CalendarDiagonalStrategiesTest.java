package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Position;
import com.algotrader.oms.OrderRequest;
import com.algotrader.strategy.base.MarketSnapshot;
import com.algotrader.strategy.impl.CalendarSpreadConfig;
import com.algotrader.strategy.impl.CalendarSpreadStrategy;
import com.algotrader.strategy.impl.DiagonalSpreadConfig;
import com.algotrader.strategy.impl.DiagonalSpreadStrategy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for calendar and diagonal spread strategies added in Phase 11.4:
 * CalendarSpreadStrategy and DiagonalSpreadStrategy.
 * Both use multi-expiry legs (different near/far expiry dates).
 */
class CalendarDiagonalStrategiesTest {

    // ========================
    // CALENDAR SPREAD
    // ========================

    @Nested
    @DisplayName("CalendarSpreadStrategy")
    class CalendarSpreadTests {

        private TestableCalendarSpread createStrategy() {
            CalendarSpreadConfig config = CalendarSpreadConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .strikeOffset(BigDecimal.ZERO) // ATM calendar
                    .optionType("CE")
                    .nearExpiry(LocalDate.of(2025, 2, 13))
                    .farExpiry(LocalDate.of(2025, 2, 27))
                    .minEntryIV(BigDecimal.valueOf(12))
                    .rollThreshold(BigDecimal.valueOf(3000))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(2.0))
                    .minDaysToExpiry(1)
                    .entryStartTime(LocalTime.of(9, 20))
                    .entryEndTime(LocalTime.of(11, 0))
                    .build();
            return new TestableCalendarSpread("STR-CAL-001", "NIFTY-Calendar-Test", config);
        }

        @Test
        @DisplayName("Type is CALENDAR_SPREAD with 5-min interval")
        void identity() {
            TestableCalendarSpread strategy = createStrategy();
            assertThat(strategy.getType()).isEqualTo(StrategyType.CALENDAR_SPREAD);
            assertThat(strategy.getMonitoringInterval()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("Builds 2 legs at same strike, different expiries")
        void legConstruction() {
            TestableCalendarSpread strategy = createStrategy();
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).hasSize(2);
            // Sell near-term
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22000CE-NEAR");
            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.SELL);
            // Buy far-term
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY22000CE-FAR");
            assertThat(orders.get(1).getSide()).isEqualTo(OrderSide.BUY);
        }

        @Test
        @DisplayName("Both legs at the same strike (horizontal spread)")
        void sameStrike() {
            TestableCalendarSpread strategy = createStrategy();
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            // Both should have "22000" in the symbol
            assertThat(orders.get(0).getTradingSymbol()).contains("22000");
            assertThat(orders.get(1).getTradingSymbol()).contains("22000");
        }

        @Test
        @DisplayName("Entry requires IV >= minEntryIV")
        void entryRequiresIV() {
            TestableCalendarSpread strategy = createStrategy();
            strategy.forceWithinEntryWindow(true);

            MarketSnapshot lowIV = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .atmIV(BigDecimal.valueOf(8))
                    .timestamp(LocalDateTime.now())
                    .build();
            assertThat(strategy.callShouldEnter(lowIV)).isFalse();

            MarketSnapshot highIV = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .atmIV(BigDecimal.valueOf(15))
                    .timestamp(LocalDateTime.now())
                    .build();
            assertThat(strategy.callShouldEnter(highIV)).isTrue();
        }

        @Test
        @DisplayName("Records CALENDAR_CLOSE on P&L breach (closes entirely)")
        void closeOnPnlBreach() {
            TestableCalendarSpread strategy = createStrategy();
            strategy.arm();
            strategy.forceStatus(StrategyStatus.ACTIVE);

            Position position = Position.builder()
                    .id("POS-1")
                    .strategyId("STR-CAL-001")
                    .tradingSymbol("NIFTY22000CE-NEAR")
                    .quantity(-75)
                    .unrealizedPnl(BigDecimal.valueOf(-3500))
                    .realizedPnl(BigDecimal.ZERO)
                    .lastUpdated(LocalDateTime.now())
                    .build();
            strategy.addPosition(position);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22200))
                    .timestamp(LocalDateTime.now())
                    .build();

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isEqualTo("CALENDAR_CLOSE");
        }

        @Test
        @DisplayName("No adjustment when P&L above threshold")
        void noAdjustmentWhenPnlOK() {
            TestableCalendarSpread strategy = createStrategy();
            strategy.arm();
            strategy.forceStatus(StrategyStatus.ACTIVE);

            Position position = Position.builder()
                    .id("POS-1")
                    .strategyId("STR-CAL-001")
                    .tradingSymbol("NIFTY22000CE-NEAR")
                    .quantity(-75)
                    .unrealizedPnl(BigDecimal.valueOf(-1000))
                    .realizedPnl(BigDecimal.ZERO)
                    .lastUpdated(LocalDateTime.now())
                    .build();
            strategy.addPosition(position);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isNull();
        }

        @Test
        @DisplayName("Morphs to DIAGONAL_SPREAD")
        void supportedMorphs() {
            TestableCalendarSpread strategy = createStrategy();
            assertThat(strategy.supportedMorphs()).containsExactly(StrategyType.DIAGONAL_SPREAD);
        }
    }

    // ========================
    // DIAGONAL SPREAD
    // ========================

    @Nested
    @DisplayName("DiagonalSpreadStrategy")
    class DiagonalSpreadTests {

        private TestableDiagonalSpread createStrategy() {
            DiagonalSpreadConfig config = DiagonalSpreadConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .nearStrikeOffset(BigDecimal.valueOf(200)) // Sell at ATM + 200 (OTM)
                    .farStrikeOffset(BigDecimal.ZERO) // Buy at ATM
                    .optionType("CE")
                    .nearExpiry(LocalDate.of(2025, 2, 13))
                    .farExpiry(LocalDate.of(2025, 2, 27))
                    .minEntryIV(BigDecimal.valueOf(12))
                    .rollThreshold(BigDecimal.valueOf(3000))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(2.0))
                    .minDaysToExpiry(1)
                    .entryStartTime(LocalTime.of(9, 20))
                    .entryEndTime(LocalTime.of(11, 0))
                    .build();
            return new TestableDiagonalSpread("STR-DG-001", "NIFTY-Diagonal-Test", config);
        }

        @Test
        @DisplayName("Type is DIAGONAL_SPREAD with 5-min interval")
        void identity() {
            TestableDiagonalSpread strategy = createStrategy();
            assertThat(strategy.getType()).isEqualTo(StrategyType.DIAGONAL_SPREAD);
            assertThat(strategy.getMonitoringInterval()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("Builds 2 legs at different strikes AND different expiries")
        void legConstruction() {
            TestableDiagonalSpread strategy = createStrategy();
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).hasSize(2);
            // Sell near-term at ATM + 200
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22200CE-NEAR");
            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.SELL);
            // Buy far-term at ATM
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY22000CE-FAR");
            assertThat(orders.get(1).getSide()).isEqualTo(OrderSide.BUY);
        }

        @Test
        @DisplayName("Near and far legs have different strikes (diagonal property)")
        void differentStrikes() {
            TestableDiagonalSpread strategy = createStrategy();
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            // Near: NIFTY22200CE-NEAR (ATM+200)
            // Far: NIFTY22000CE-FAR (ATM)
            // Strikes should be different
            assertThat(orders.get(0).getTradingSymbol())
                    .isNotEqualTo(orders.get(1).getTradingSymbol().replace("FAR", "NEAR"));
        }

        @Test
        @DisplayName("Records DIAGONAL_CLOSE on P&L breach")
        void closeOnPnlBreach() {
            TestableDiagonalSpread strategy = createStrategy();
            strategy.arm();
            strategy.forceStatus(StrategyStatus.ACTIVE);

            Position position = Position.builder()
                    .id("POS-1")
                    .strategyId("STR-DG-001")
                    .tradingSymbol("NIFTY22200CE-NEAR")
                    .quantity(-75)
                    .unrealizedPnl(BigDecimal.valueOf(-3500))
                    .realizedPnl(BigDecimal.ZERO)
                    .lastUpdated(LocalDateTime.now())
                    .build();
            strategy.addPosition(position);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22300))
                    .timestamp(LocalDateTime.now())
                    .build();

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isEqualTo("DIAGONAL_CLOSE");
        }

        @Test
        @DisplayName("Exit on target profit")
        void exitOnTarget() {
            TestableDiagonalSpread strategy = createStrategy();
            strategy.forceEntryPremium(BigDecimal.valueOf(200));

            Position position = Position.builder()
                    .id("POS-1")
                    .strategyId("STR-DG-001")
                    .tradingSymbol("NIFTY22200CE-NEAR")
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
        @DisplayName("Morphs to CALENDAR_SPREAD")
        void supportedMorphs() {
            TestableDiagonalSpread strategy = createStrategy();
            assertThat(strategy.supportedMorphs()).containsExactly(StrategyType.CALENDAR_SPREAD);
        }
    }

    // ========================
    // TESTABLE SUBCLASSES
    // ========================

    static class TestableCalendarSpread extends CalendarSpreadStrategy {
        private Boolean withinEntryWindowOverride;
        private String lastAdjustmentType;

        TestableCalendarSpread(String id, String name, CalendarSpreadConfig config) {
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

    static class TestableDiagonalSpread extends DiagonalSpreadStrategy {
        private Boolean withinEntryWindowOverride;
        private String lastAdjustmentType;

        TestableDiagonalSpread(String id, String name, DiagonalSpreadConfig config) {
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
