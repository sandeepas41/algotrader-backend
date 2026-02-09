package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Greeks;
import com.algotrader.domain.model.Position;
import com.algotrader.oms.OrderRequest;
import com.algotrader.strategy.base.MarketSnapshot;
import com.algotrader.strategy.impl.StraddleConfig;
import com.algotrader.strategy.impl.StraddleStrategy;
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
 * Unit tests for StraddleStrategy covering entry conditions, ATM strike selection,
 * exit conditions (target/SL/DTE), delta-based shift adjustment, entry order construction,
 * and supported morphs.
 *
 * <p>Uses a TestableStraddleStrategy subclass to expose protected methods and bypass
 * time-dependent checks (entry window, monitoring interval) for deterministic testing.
 */
class StraddleStrategyTest {

    private static final String STRATEGY_ID = "STR-TEST001";
    private static final String STRATEGY_NAME = "NIFTY-Straddle-Test";

    private StraddleConfig config;
    private TestableStraddleStrategy strategy;

    @BeforeEach
    void setUp() {
        config = StraddleConfig.builder()
                .underlying("NIFTY")
                .lots(1)
                .strikeInterval(BigDecimal.valueOf(50))
                .minIV(BigDecimal.valueOf(12))
                .shiftDeltaThreshold(BigDecimal.valueOf(0.35))
                .targetPercent(BigDecimal.valueOf(0.5))
                .stopLossMultiplier(BigDecimal.valueOf(1.5))
                .minDaysToExpiry(1)
                .entryStartTime(LocalTime.of(9, 20))
                .entryEndTime(LocalTime.of(10, 0))
                .build();

        strategy = new TestableStraddleStrategy(STRATEGY_ID, STRATEGY_NAME, config);
    }

    // ========================
    // IDENTITY & TYPE
    // ========================

    @Nested
    @DisplayName("Identity & Type")
    class IdentityAndType {

        @Test
        @DisplayName("Type is STRADDLE")
        void typeIsStraddle() {
            assertThat(strategy.getType()).isEqualTo(StrategyType.STRADDLE);
        }

        @Test
        @DisplayName("Monitoring interval is 5 minutes (positional)")
        void monitoringIntervalIs5Minutes() {
            assertThat(strategy.getMonitoringInterval()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("Underlying comes from config")
        void underlyingFromConfig() {
            assertThat(strategy.getUnderlying()).isEqualTo("NIFTY");
        }

        @Test
        @DisplayName("Initial status is CREATED")
        void initialStatusIsCreated() {
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.CREATED);
        }
    }

    // ========================
    // ENTRY CONDITIONS
    // ========================

    @Nested
    @DisplayName("Entry Conditions (shouldEnter)")
    class EntryConditions {

        @Test
        @DisplayName("Returns true when IV >= minIV and within entry window")
        void entryWhenIVAboveThreshold() {
            strategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .atmIV(BigDecimal.valueOf(15))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isTrue();
        }

        @Test
        @DisplayName("Returns false when IV < minIV")
        void noEntryWhenIVBelowThreshold() {
            strategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .atmIV(BigDecimal.valueOf(10))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Returns false when IV equals minIV (boundary: >= required)")
        void entryAtExactMinIV() {
            strategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .atmIV(BigDecimal.valueOf(12))
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
                    .atmIV(BigDecimal.valueOf(20))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Returns false when ATM IV is null (no IV data)")
        void noEntryWhenIVNull() {
            strategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .atmIV(null)
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Returns true when minIV is null (no IV requirement)")
        void entryWhenMinIVNotConfigured() {
            StraddleConfig noIVConfig = StraddleConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .minIV(null)
                    .shiftDeltaThreshold(BigDecimal.valueOf(0.35))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(1.5))
                    .build();

            TestableStraddleStrategy noIVStrategy =
                    new TestableStraddleStrategy(STRATEGY_ID, STRATEGY_NAME, noIVConfig);
            noIVStrategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .atmIV(null)
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(noIVStrategy.callShouldEnter(snapshot)).isTrue();
        }
    }

    // ========================
    // ATM STRIKE SELECTION
    // ========================

    @Nested
    @DisplayName("ATM Strike Selection (buildEntryOrders)")
    class ATMStrikeSelection {

        @Test
        @DisplayName("Spot at 22035 rounds to 22050 ATM strike (interval 50)")
        void roundsUpToNearest50() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22035))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).hasSize(2);
            assertThat(orders.get(0).getTradingSymbol()).contains("22050");
            assertThat(orders.get(1).getTradingSymbol()).contains("22050");
        }

        @Test
        @DisplayName("Spot at 22025 rounds to 22050 ATM strike (rounds to nearest)")
        void roundsToNearestStrike() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22025))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).hasSize(2);
            // 22025/50 = 440.5, rounds to 441 * 50 = 22050
            assertThat(orders.get(0).getTradingSymbol()).contains("22050");
        }

        @Test
        @DisplayName("Spot at 22000 stays at 22000 (exact multiple)")
        void exactMultipleStaysUnchanged() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).hasSize(2);
            assertThat(orders.get(0).getTradingSymbol()).contains("22000");
        }

        @Test
        @DisplayName("Spot at 22010 rounds down to 22000 (closer to 22000 than 22050)")
        void roundsDownWhenCloserToLower() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22010))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).hasSize(2);
            // 22010/50 = 440.2, rounds to 440 * 50 = 22000
            assertThat(orders.get(0).getTradingSymbol()).contains("22000");
        }
    }

    // ========================
    // ENTRY ORDER CONSTRUCTION
    // ========================

    @Nested
    @DisplayName("Entry Order Construction")
    class EntryOrderConstruction {

        @Test
        @DisplayName("Produces 2 sell-side legs: CE and PE")
        void twoSellLegs() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).hasSize(2);
            assertThat(orders).allMatch(o -> o.getSide() == OrderSide.SELL);
        }

        @Test
        @DisplayName("First leg is CE, second is PE at same ATM strike")
        void ceAndPeAtSameStrike() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22000CE");
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY22000PE");
        }

        @Test
        @DisplayName("Quantity is 1 per leg (scaling done by BaseStrategy)")
        void quantityIsOne() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).allMatch(o -> o.getQuantity() == 1);
        }

        @Test
        @DisplayName("Order type is MARKET")
        void orderTypeIsMarket() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

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
        @DisplayName("Exit when target profit reached (P&L >= 50% of entry premium)")
        void exitOnTargetReached() {
            // Entry premium = 100, target = 50% => exit when P&L >= 50
            strategy.forceEntryPremium(BigDecimal.valueOf(100));
            addPositionWithPnl(BigDecimal.valueOf(50), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("No exit when P&L below target")
        void noExitBelowTarget() {
            strategy.forceEntryPremium(BigDecimal.valueOf(100));
            addPositionWithPnl(BigDecimal.valueOf(40), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Exit when stop loss hit (P&L <= -(1.5x entry premium))")
        void exitOnStopLoss() {
            // Entry premium = 100, SL multiplier = 1.5 => exit when P&L <= -150
            strategy.forceEntryPremium(BigDecimal.valueOf(100));
            addPositionWithPnl(BigDecimal.valueOf(-160), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("No exit when loss within stop loss limit")
        void noExitWithinStopLoss() {
            strategy.forceEntryPremium(BigDecimal.valueOf(100));
            addPositionWithPnl(BigDecimal.valueOf(-100), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isFalse();
        }

        @Test
        @DisplayName("No exit when entry premium is null (no baseline for target/SL)")
        void noExitWhenNoPremium() {
            addPositionWithPnl(BigDecimal.valueOf(1000), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Exit at exact target boundary (>=)")
        void exitAtExactTarget() {
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            // Target = 200 * 0.5 = 100, P&L = exactly 100
            addPositionWithPnl(BigDecimal.valueOf(100), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("Exit at exact stop loss boundary (<=)")
        void exitAtExactStopLoss() {
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            // SL = -(200 * 1.5) = -300, P&L = exactly -300
            addPositionWithPnl(BigDecimal.valueOf(-300), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }
    }

    // ========================
    // ADJUSTMENT (DELTA-BASED SHIFT)
    // ========================

    @Nested
    @DisplayName("Delta-Based Shift Adjustment")
    class DeltaBasedShift {

        private MarketSnapshot snapshot;

        @BeforeEach
        void setUp() {
            snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22200))
                    .timestamp(LocalDateTime.now())
                    .build();

            // Strategy must be ACTIVE for adjustments
            strategy.arm();
            strategy.forceStatus(StrategyStatus.ACTIVE);
        }

        @Test
        @DisplayName("Triggers shift when absolute delta exceeds threshold")
        void shiftsWhenDeltaExceedsThreshold() {
            // Delta = 0.40 > threshold 0.35 => should shift
            addPositionWithDelta(BigDecimal.valueOf(0.40));

            strategy.callAdjust(snapshot);

            // After shift: initiateClose is called, moving to CLOSING
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.CLOSING);
        }

        @Test
        @DisplayName("Triggers shift on negative delta breach")
        void shiftsOnNegativeDeltaBreach() {
            // Delta = -0.40 > threshold 0.35 (absolute) => should shift
            addPositionWithDelta(BigDecimal.valueOf(-0.40));

            strategy.callAdjust(snapshot);

            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.CLOSING);
        }

        @Test
        @DisplayName("No shift when delta within threshold")
        void noShiftWhenDeltaWithinThreshold() {
            addPositionWithDelta(BigDecimal.valueOf(0.20));

            strategy.callAdjust(snapshot);

            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.ACTIVE);
        }

        @Test
        @DisplayName("No shift at exact threshold (> required, not >=)")
        void noShiftAtExactThreshold() {
            addPositionWithDelta(BigDecimal.valueOf(0.35));

            strategy.callAdjust(snapshot);

            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.ACTIVE);
        }

        @Test
        @DisplayName("No shift when shiftDeltaThreshold is null")
        void noShiftWhenThresholdNull() {
            StraddleConfig noShiftConfig = StraddleConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .minIV(BigDecimal.valueOf(12))
                    .shiftDeltaThreshold(null)
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(1.5))
                    .build();

            TestableStraddleStrategy noShiftStrategy =
                    new TestableStraddleStrategy("STR-NS", "No-Shift", noShiftConfig);
            noShiftStrategy.arm();
            noShiftStrategy.forceStatus(StrategyStatus.ACTIVE);

            addPositionWithDelta(noShiftStrategy, BigDecimal.valueOf(0.50));

            noShiftStrategy.callAdjust(snapshot);

            assertThat(noShiftStrategy.getStatus()).isEqualTo(StrategyStatus.ACTIVE);
        }
    }

    // ========================
    // SUPPORTED MORPHS
    // ========================

    @Nested
    @DisplayName("Supported Morphs")
    class SupportedMorphs {

        @Test
        @DisplayName("Can morph to STRANGLE, IRON_CONDOR, IRON_BUTTERFLY")
        void supportedMorphTypes() {
            List<StrategyType> morphs = strategy.supportedMorphs();

            assertThat(morphs)
                    .containsExactlyInAnyOrder(
                            StrategyType.STRANGLE, StrategyType.IRON_CONDOR, StrategyType.IRON_BUTTERFLY);
        }
    }

    // ========================
    // HELPERS
    // ========================

    private void addPositionWithPnl(BigDecimal unrealizedPnl, BigDecimal realizedPnl) {
        Position position = Position.builder()
                .id("POS-" + System.nanoTime())
                .strategyId(STRATEGY_ID)
                .tradingSymbol("NIFTY22000CE")
                .quantity(-75)
                .unrealizedPnl(unrealizedPnl)
                .realizedPnl(realizedPnl)
                .lastUpdated(LocalDateTime.now())
                .build();
        strategy.addPosition(position);
    }

    private void addPositionWithDelta(BigDecimal delta) {
        addPositionWithDelta(strategy, delta);
    }

    private void addPositionWithDelta(TestableStraddleStrategy targetStrategy, BigDecimal delta) {
        Greeks greeks = Greeks.builder()
                .delta(delta)
                .gamma(BigDecimal.ZERO)
                .theta(BigDecimal.ZERO)
                .vega(BigDecimal.ZERO)
                .iv(BigDecimal.valueOf(15))
                .build();

        Position position = Position.builder()
                .id("POS-" + System.nanoTime())
                .strategyId(targetStrategy.getId())
                .tradingSymbol("NIFTY22000CE")
                .quantity(1) // quantity=1 so delta * quantity = delta
                .greeks(greeks)
                .unrealizedPnl(BigDecimal.ZERO)
                .realizedPnl(BigDecimal.ZERO)
                .lastUpdated(LocalDateTime.now())
                .build();
        targetStrategy.addPosition(position);
    }

    // ========================
    // TESTABLE SUBCLASS
    // ========================

    /**
     * Exposes protected methods for testing and provides control over
     * time-dependent behavior (entry window, monitoring interval).
     */
    static class TestableStraddleStrategy extends StraddleStrategy {

        private Boolean withinEntryWindowOverride;

        TestableStraddleStrategy(String id, String name, StraddleConfig config) {
            super(id, name, config);
        }

        /** Force the entry window check to return a specific value. */
        void forceWithinEntryWindow(boolean within) {
            this.withinEntryWindowOverride = within;
        }

        /** Force strategy into a specific status for testing. */
        void forceStatus(StrategyStatus status) {
            this.status = status;
        }

        /** Force entry premium for exit condition tests. */
        void forceEntryPremium(BigDecimal premium) {
            this.entryPremium = premium;
        }

        @Override
        protected boolean isWithinEntryWindow() {
            if (withinEntryWindowOverride != null) {
                return withinEntryWindowOverride;
            }
            return super.isWithinEntryWindow();
        }

        // Delegate methods to expose protected/package-private methods
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
