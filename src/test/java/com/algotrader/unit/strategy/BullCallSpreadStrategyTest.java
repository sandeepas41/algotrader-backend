package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Position;
import com.algotrader.oms.OrderRequest;
import com.algotrader.strategy.base.MarketSnapshot;
import com.algotrader.strategy.impl.BullCallSpreadConfig;
import com.algotrader.strategy.impl.BullCallSpreadStrategy;
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
 * Unit tests for BullCallSpreadStrategy covering entry conditions (time window + min spot),
 * 2-leg strike construction, exit conditions (target/SL/DTE), P&L-based roll-down adjustment,
 * and supported morphs.
 */
class BullCallSpreadStrategyTest {

    private static final String STRATEGY_ID = "STR-BCS-001";
    private static final String STRATEGY_NAME = "NIFTY-BCS-Test";

    private BullCallSpreadConfig config;
    private TestableBullCallSpreadStrategy strategy;

    @BeforeEach
    void setUp() {
        config = BullCallSpreadConfig.builder()
                .underlying("NIFTY")
                .lots(1)
                .strikeInterval(BigDecimal.valueOf(50))
                .buyOffset(BigDecimal.ZERO)
                .sellOffset(BigDecimal.valueOf(200))
                .minSpotForEntry(BigDecimal.valueOf(21500))
                .rollThreshold(BigDecimal.valueOf(3000))
                .targetPercent(BigDecimal.valueOf(0.6))
                .stopLossMultiplier(BigDecimal.valueOf(2.0))
                .minDaysToExpiry(1)
                .entryStartTime(LocalTime.of(9, 20))
                .entryEndTime(LocalTime.of(14, 0))
                .build();

        strategy = new TestableBullCallSpreadStrategy(STRATEGY_ID, STRATEGY_NAME, config);
    }

    // ========================
    // IDENTITY & TYPE
    // ========================

    @Nested
    @DisplayName("Identity & Type")
    class IdentityAndType {

        @Test
        @DisplayName("Type is BULL_CALL_SPREAD")
        void typeIsBullCallSpread() {
            assertThat(strategy.getType()).isEqualTo(StrategyType.BULL_CALL_SPREAD);
        }

        @Test
        @DisplayName("Monitoring interval is 5 minutes")
        void monitoringInterval() {
            assertThat(strategy.getMonitoringInterval()).isEqualTo(Duration.ofMinutes(5));
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
        @DisplayName("Returns true when spot >= minSpot and within entry window")
        void entryWhenConditionsMet() {
            strategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isTrue();
        }

        @Test
        @DisplayName("Returns false when spot < minSpotForEntry")
        void noEntryWhenSpotLow() {
            strategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(21000))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Returns true at exact minSpotForEntry boundary")
        void entryAtExactMinSpot() {
            strategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(21500))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isTrue();
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

        @Test
        @DisplayName("Entry allowed when minSpotForEntry is null (no min spot requirement)")
        void entryWhenMinSpotNull() {
            BullCallSpreadConfig noMinSpotConfig = BullCallSpreadConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .buyOffset(BigDecimal.ZERO)
                    .sellOffset(BigDecimal.valueOf(200))
                    .minSpotForEntry(null)
                    .build();

            TestableBullCallSpreadStrategy noMinSpotStrategy =
                    new TestableBullCallSpreadStrategy("STR-BCS-NM", "No-MinSpot", noMinSpotConfig);
            noMinSpotStrategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(18000))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(noMinSpotStrategy.callShouldEnter(snapshot)).isTrue();
        }

        @Test
        @DisplayName("Returns false when spot price is null")
        void noEntryWhenSpotNull() {
            strategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(null)
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isFalse();
        }
    }

    // ========================
    // 2-LEG CONSTRUCTION
    // ========================

    @Nested
    @DisplayName("Entry Order Construction (2 legs)")
    class EntryOrderConstruction {

        @Test
        @DisplayName("Produces exactly 2 legs")
        void twoLegs() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).hasSize(2);
        }

        @Test
        @DisplayName("Correct strikes: buy CE ATM, sell CE ATM+200")
        void correctStrikes() {
            // ATM = 22000, buyOffset=0, sellOffset=200
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22000CE"); // buy call
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY22200CE"); // sell call
        }

        @Test
        @DisplayName("Correct sides: buy lower CE, sell higher CE")
        void correctSides() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.BUY); // buy lower
            assertThat(orders.get(1).getSide()).isEqualTo(OrderSide.SELL); // sell higher
        }

        @Test
        @DisplayName("ATM rounding applied: spot 22035 -> ATM 22050")
        void atmRoundingApplied() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22035))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            // ATM = 22050, buy CE = 22050, sell CE = 22250
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22050CE");
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY22250CE");
        }

        @Test
        @DisplayName("All legs are MARKET orders with quantity 1")
        void marketOrdersQuantity1() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).allMatch(o -> o.getQuantity() == 1);
            assertThat(orders).allMatch(o -> o.getType() == com.algotrader.domain.enums.OrderType.MARKET);
        }
    }

    // ========================
    // EXIT CONDITIONS
    // ========================

    @Nested
    @DisplayName("Exit Conditions (shouldExit)")
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
        @DisplayName("Exit on target: P&L >= 60% of entry premium")
        void exitOnTarget() {
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            addPositionWithPnl(BigDecimal.valueOf(120), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("No exit when P&L below target")
        void noExitBelowTarget() {
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            addPositionWithPnl(BigDecimal.valueOf(100), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Exit on stop loss: P&L <= -(2x entry premium)")
        void exitOnStopLoss() {
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            addPositionWithPnl(BigDecimal.valueOf(-400), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("No exit when loss within SL limit")
        void noExitWithinSL() {
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            addPositionWithPnl(BigDecimal.valueOf(-300), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isFalse();
        }

        @Test
        @DisplayName("No exit when entry premium is null")
        void noExitWithNoPremium() {
            addPositionWithPnl(BigDecimal.valueOf(1000), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isFalse();
        }
    }

    // ========================
    // P&L ROLL ADJUSTMENT
    // ========================

    @Nested
    @DisplayName("P&L Roll-Down Adjustment")
    class PnlRollAdjustment {

        private MarketSnapshot snapshot;

        @BeforeEach
        void setUp() {
            snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            strategy.arm();
            strategy.forceStatus(StrategyStatus.ACTIVE);
        }

        @Test
        @DisplayName("Records ROLL_BUY_DOWN when P&L below -rollThreshold and buy leg exists")
        void rollBuyDownOnLoss() {
            // rollThreshold=3000, so roll when P&L <= -3000
            addPositionWithPnlAndSymbol(BigDecimal.valueOf(-3000), "NIFTY22000CE", 75);

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isEqualTo("ROLL_BUY_DOWN");
        }

        @Test
        @DisplayName("No adjustment when P&L above -rollThreshold")
        void noAdjustmentWhenAboveThreshold() {
            addPositionWithPnlAndSymbol(BigDecimal.valueOf(-2500), "NIFTY22000CE", 75);

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isNull();
        }

        @Test
        @DisplayName("No adjustment when no buy leg exists")
        void noAdjustmentWhenNoBuyLeg() {
            // Only short position (qty < 0), no long position for roll
            addPositionWithPnlAndSymbol(BigDecimal.valueOf(-4000), "NIFTY22200CE", -75);

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isNull();
        }

        @Test
        @DisplayName("No adjustment when rollThreshold is null")
        void noAdjustmentWhenThresholdNull() {
            BullCallSpreadConfig noRollConfig = BullCallSpreadConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .buyOffset(BigDecimal.ZERO)
                    .sellOffset(BigDecimal.valueOf(200))
                    .rollThreshold(null)
                    .build();

            TestableBullCallSpreadStrategy noRollStrategy =
                    new TestableBullCallSpreadStrategy("STR-BCS-NR", "No-Roll", noRollConfig);
            noRollStrategy.arm();
            noRollStrategy.forceStatus(StrategyStatus.ACTIVE);

            addPositionWithPnlAndSymbol(noRollStrategy, BigDecimal.valueOf(-5000), "NIFTY22000CE", 75);

            noRollStrategy.callAdjust(snapshot);

            assertThat(noRollStrategy.getLastAdjustmentType()).isNull();
        }

        @Test
        @DisplayName("Roll triggered at exact -rollThreshold boundary")
        void rollAtExactThreshold() {
            // P&L = -3000 exactly equals -rollThreshold => should trigger (<=)
            addPositionWithPnlAndSymbol(BigDecimal.valueOf(-3000), "NIFTY22000CE", 75);

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isEqualTo("ROLL_BUY_DOWN");
        }
    }

    // ========================
    // SUPPORTED MORPHS
    // ========================

    @Nested
    @DisplayName("Supported Morphs")
    class SupportedMorphs {

        @Test
        @DisplayName("Can morph to BEAR_CALL_SPREAD and IRON_CONDOR")
        void supportedMorphTypes() {
            List<StrategyType> morphs = strategy.supportedMorphs();

            assertThat(morphs).containsExactlyInAnyOrder(StrategyType.BEAR_CALL_SPREAD, StrategyType.IRON_CONDOR);
        }
    }

    // ========================
    // HELPERS
    // ========================

    private void addPositionWithPnl(BigDecimal unrealizedPnl, BigDecimal realizedPnl) {
        Position position = Position.builder()
                .id("POS-" + System.nanoTime())
                .tradingSymbol("NIFTY22000CE")
                .quantity(75)
                .unrealizedPnl(unrealizedPnl)
                .realizedPnl(realizedPnl)
                .lastUpdated(LocalDateTime.now())
                .build();
        strategy.addPosition(position);
    }

    private void addPositionWithPnlAndSymbol(BigDecimal unrealizedPnl, String symbol, int quantity) {
        addPositionWithPnlAndSymbol(strategy, unrealizedPnl, symbol, quantity);
    }

    private void addPositionWithPnlAndSymbol(
            TestableBullCallSpreadStrategy targetStrategy, BigDecimal unrealizedPnl, String symbol, int quantity) {
        Position position = Position.builder()
                .id("POS-" + System.nanoTime())
                .tradingSymbol(symbol)
                .quantity(quantity)
                .unrealizedPnl(unrealizedPnl)
                .realizedPnl(BigDecimal.ZERO)
                .lastUpdated(LocalDateTime.now())
                .build();
        targetStrategy.addPosition(position);
    }

    // ========================
    // TESTABLE SUBCLASS
    // ========================

    /**
     * Exposes protected methods and tracks adjustment types for assertions.
     */
    static class TestableBullCallSpreadStrategy extends BullCallSpreadStrategy {

        private Boolean withinEntryWindowOverride;
        private String lastAdjustmentType;

        TestableBullCallSpreadStrategy(String id, String name, BullCallSpreadConfig config) {
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
