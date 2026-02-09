package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Greeks;
import com.algotrader.domain.model.Position;
import com.algotrader.oms.OrderRequest;
import com.algotrader.strategy.base.MarketSnapshot;
import com.algotrader.strategy.impl.StrangleConfig;
import com.algotrader.strategy.impl.StrangleStrategy;
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
 * Unit tests for StrangleStrategy covering entry conditions (IV + time window),
 * 2-leg OTM strike construction, exit conditions (target/SL/DTE), delta shift
 * adjustment, and supported morphs.
 */
class StrangleStrategyTest {

    private static final String STRATEGY_ID = "STR-STR-001";
    private static final String STRATEGY_NAME = "NIFTY-Strangle-Test";

    private StrangleConfig config;
    private TestableStrangleStrategy strategy;

    @BeforeEach
    void setUp() {
        config = StrangleConfig.builder()
                .underlying("NIFTY")
                .lots(1)
                .strikeInterval(BigDecimal.valueOf(50))
                .callOffset(BigDecimal.valueOf(200))
                .putOffset(BigDecimal.valueOf(200))
                .minIV(BigDecimal.valueOf(14))
                .shiftDeltaThreshold(BigDecimal.valueOf(0.35))
                .targetPercent(BigDecimal.valueOf(0.5))
                .stopLossMultiplier(BigDecimal.valueOf(2.0))
                .minDaysToExpiry(2)
                .entryStartTime(LocalTime.of(9, 20))
                .entryEndTime(LocalTime.of(11, 0))
                .build();

        strategy = new TestableStrangleStrategy(STRATEGY_ID, STRATEGY_NAME, config);
    }

    @Nested
    @DisplayName("Identity & Type")
    class IdentityAndType {

        @Test
        @DisplayName("Type is STRANGLE")
        void typeIsStrangle() {
            assertThat(strategy.getType()).isEqualTo(StrategyType.STRANGLE);
        }

        @Test
        @DisplayName("Monitoring interval is 5 minutes")
        void monitoringInterval() {
            assertThat(strategy.getMonitoringInterval()).isEqualTo(Duration.ofMinutes(5));
        }
    }

    @Nested
    @DisplayName("Entry Conditions")
    class EntryConditions {

        @Test
        @DisplayName("Returns true when IV >= minIV and within entry window")
        void entryWhenConditionsMet() {
            strategy.forceWithinEntryWindow(true);
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .atmIV(BigDecimal.valueOf(16))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isTrue();
        }

        @Test
        @DisplayName("Returns false when IV < minIV")
        void noEntryWhenIVLow() {
            strategy.forceWithinEntryWindow(true);
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .atmIV(BigDecimal.valueOf(10))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Returns false when outside entry window")
        void noEntryOutsideWindow() {
            strategy.forceWithinEntryWindow(false);
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .atmIV(BigDecimal.valueOf(20))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isFalse();
        }
    }

    @Nested
    @DisplayName("Entry Order Construction (2 OTM legs)")
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
        @DisplayName("Correct strikes: sell CE at ATM+200, sell PE at ATM-200")
        void correctStrikes() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22200CE");
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY21800PE");
        }

        @Test
        @DisplayName("Both legs are SELL orders")
        void bothSellSide() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.SELL);
            assertThat(orders.get(1).getSide()).isEqualTo(OrderSide.SELL);
        }

        @Test
        @DisplayName("ATM rounding applied: spot 22035 -> ATM 22050")
        void atmRounding() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22035))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22250CE");
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY21850PE");
        }
    }

    @Nested
    @DisplayName("Exit Conditions")
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
        @DisplayName("Exit on target profit")
        void exitOnTarget() {
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            addPositionWithPnl(BigDecimal.valueOf(100), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("Exit on stop loss")
        void exitOnStopLoss() {
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            addPositionWithPnl(BigDecimal.valueOf(-400), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("No exit within limits")
        void noExitWithinLimits() {
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            addPositionWithPnl(BigDecimal.valueOf(50), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isFalse();
        }
    }

    @Nested
    @DisplayName("Delta Shift Adjustment")
    class DeltaShiftAdjustment {

        @Test
        @DisplayName("Records STRANGLE_SHIFT when delta exceeds threshold")
        void shiftOnDeltaBreach() {
            strategy.arm();
            strategy.forceStatus(StrategyStatus.ACTIVE);

            // Add position with delta > 0.35 threshold
            Greeks greeks = Greeks.builder()
                    .delta(BigDecimal.valueOf(0.40))
                    .gamma(BigDecimal.ZERO)
                    .theta(BigDecimal.ZERO)
                    .vega(BigDecimal.ZERO)
                    .iv(BigDecimal.valueOf(15))
                    .build();
            Position position = Position.builder()
                    .id("POS-1")
                    .strategyId(STRATEGY_ID)
                    .tradingSymbol("NIFTY22200CE")
                    .quantity(1)
                    .greeks(greeks)
                    .unrealizedPnl(BigDecimal.ZERO)
                    .realizedPnl(BigDecimal.ZERO)
                    .lastUpdated(LocalDateTime.now())
                    .build();
            strategy.addPosition(position);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22200))
                    .timestamp(LocalDateTime.now())
                    .build();

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isEqualTo("STRANGLE_SHIFT");
        }

        @Test
        @DisplayName("No adjustment when delta within threshold")
        void noShiftWithinThreshold() {
            strategy.arm();
            strategy.forceStatus(StrategyStatus.ACTIVE);

            Greeks greeks = Greeks.builder()
                    .delta(BigDecimal.valueOf(0.20))
                    .gamma(BigDecimal.ZERO)
                    .theta(BigDecimal.ZERO)
                    .vega(BigDecimal.ZERO)
                    .iv(BigDecimal.valueOf(15))
                    .build();
            Position position = Position.builder()
                    .id("POS-1")
                    .strategyId(STRATEGY_ID)
                    .tradingSymbol("NIFTY22200CE")
                    .quantity(1)
                    .greeks(greeks)
                    .unrealizedPnl(BigDecimal.ZERO)
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
    }

    @Nested
    @DisplayName("Supported Morphs")
    class SupportedMorphs {

        @Test
        @DisplayName("Can morph to STRADDLE and IRON_CONDOR")
        void supportedMorphTypes() {
            assertThat(strategy.supportedMorphs())
                    .containsExactlyInAnyOrder(StrategyType.STRADDLE, StrategyType.IRON_CONDOR);
        }
    }

    // ========================
    // HELPERS
    // ========================

    private void addPositionWithPnl(BigDecimal unrealizedPnl, BigDecimal realizedPnl) {
        Position position = Position.builder()
                .id("POS-" + System.nanoTime())
                .strategyId(STRATEGY_ID)
                .tradingSymbol("NIFTY22200CE")
                .quantity(-75)
                .unrealizedPnl(unrealizedPnl)
                .realizedPnl(realizedPnl)
                .lastUpdated(LocalDateTime.now())
                .build();
        strategy.addPosition(position);
    }

    // ========================
    // TESTABLE SUBCLASS
    // ========================

    static class TestableStrangleStrategy extends StrangleStrategy {

        private Boolean withinEntryWindowOverride;
        private String lastAdjustmentType;

        TestableStrangleStrategy(String id, String name, StrangleConfig config) {
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
