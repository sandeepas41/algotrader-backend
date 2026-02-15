package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Greeks;
import com.algotrader.domain.model.Position;
import com.algotrader.oms.OrderRequest;
import com.algotrader.strategy.base.MarketSnapshot;
import com.algotrader.strategy.impl.IronCondorConfig;
import com.algotrader.strategy.impl.IronCondorStrategy;
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
 * Unit tests for IronCondorStrategy covering entry conditions (IV + time window),
 * 4-leg strike construction, exit conditions (target/SL/DTE), delta roll adjustment,
 * and supported morphs.
 */
class IronCondorStrategyTest {

    private static final String STRATEGY_ID = "STR-IC-001";
    private static final String STRATEGY_NAME = "NIFTY-IC-Test";

    private IronCondorConfig config;
    private TestableIronCondorStrategy strategy;

    @BeforeEach
    void setUp() {
        config = IronCondorConfig.builder()
                .underlying("NIFTY")
                .lots(1)
                .strikeInterval(BigDecimal.valueOf(50))
                .callOffset(BigDecimal.valueOf(200))
                .putOffset(BigDecimal.valueOf(200))
                .wingWidth(BigDecimal.valueOf(100))
                .minEntryIV(BigDecimal.valueOf(15))
                .deltaRollThreshold(BigDecimal.valueOf(0.30))
                .targetPercent(BigDecimal.valueOf(0.5))
                .stopLossMultiplier(BigDecimal.valueOf(2.0))
                .minDaysToExpiry(2)
                .entryStartTime(LocalTime.of(9, 20))
                .entryEndTime(LocalTime.of(11, 0))
                .build();

        strategy = new TestableIronCondorStrategy(STRATEGY_ID, STRATEGY_NAME, config);
    }

    // ========================
    // IDENTITY & TYPE
    // ========================

    @Nested
    @DisplayName("Identity & Type")
    class IdentityAndType {

        @Test
        @DisplayName("Type is IRON_CONDOR")
        void typeIsIronCondor() {
            assertThat(strategy.getType()).isEqualTo(StrategyType.IRON_CONDOR);
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
        @DisplayName("Returns true when IV >= minEntryIV and within entry window")
        void entryWhenConditionsMet() {
            strategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .atmIV(BigDecimal.valueOf(18))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isTrue();
        }

        @Test
        @DisplayName("Returns false when IV < minEntryIV")
        void noEntryWhenIVLow() {
            strategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .atmIV(BigDecimal.valueOf(12))
                    .timestamp(LocalDateTime.now())
                    .build();

            assertThat(strategy.callShouldEnter(snapshot)).isFalse();
        }

        @Test
        @DisplayName("Returns true at exact minEntryIV boundary")
        void entryAtExactMinIV() {
            strategy.forceWithinEntryWindow(true);

            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .atmIV(BigDecimal.valueOf(15))
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
        @DisplayName("Returns false when ATM IV is null")
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
        @DisplayName("Entry allowed when minEntryIV is null (no IV requirement)")
        void entryWhenMinIVNull() {
            IronCondorConfig noIVConfig = IronCondorConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .callOffset(BigDecimal.valueOf(200))
                    .putOffset(BigDecimal.valueOf(200))
                    .wingWidth(BigDecimal.valueOf(100))
                    .minEntryIV(null)
                    .build();

            TestableIronCondorStrategy noIVStrategy = new TestableIronCondorStrategy("STR-IC-NV", "No-IV", noIVConfig);
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
    // 4-LEG CONSTRUCTION
    // ========================

    @Nested
    @DisplayName("Entry Order Construction (4 legs)")
    class EntryOrderConstruction {

        @Test
        @DisplayName("Produces exactly 4 legs")
        void fourLegs() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders).hasSize(4);
        }

        @Test
        @DisplayName("Correct strikes: sell CE ATM+200, buy CE ATM+300, sell PE ATM-200, buy PE ATM-300")
        void correctStrikes() {
            // ATM = 22000, callOffset=200, putOffset=200, wingWidth=100
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22200CE"); // sell call
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY22300CE"); // buy call wing
            assertThat(orders.get(2).getTradingSymbol()).isEqualTo("NIFTY21800PE"); // sell put
            assertThat(orders.get(3).getTradingSymbol()).isEqualTo("NIFTY21700PE"); // buy put wing
        }

        @Test
        @DisplayName("Correct sides: sell-buy on call side, sell-buy on put side")
        void correctSides() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22000))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.SELL); // sell call
            assertThat(orders.get(1).getSide()).isEqualTo(OrderSide.BUY); // buy call wing
            assertThat(orders.get(2).getSide()).isEqualTo(OrderSide.SELL); // sell put
            assertThat(orders.get(3).getSide()).isEqualTo(OrderSide.BUY); // buy put wing
        }

        @Test
        @DisplayName("ATM rounding applied: spot 22035 -> ATM 22050")
        void atmRoundingApplied() {
            MarketSnapshot snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22035))
                    .timestamp(LocalDateTime.now())
                    .build();

            List<OrderRequest> orders = strategy.callBuildEntryOrders(snapshot);

            // ATM = 22050, sell CE = 22250, buy CE = 22350
            assertThat(orders.get(0).getTradingSymbol()).isEqualTo("NIFTY22250CE");
            assertThat(orders.get(1).getTradingSymbol()).isEqualTo("NIFTY22350CE");
            // sell PE = 21850, buy PE = 21750
            assertThat(orders.get(2).getTradingSymbol()).isEqualTo("NIFTY21850PE");
            assertThat(orders.get(3).getTradingSymbol()).isEqualTo("NIFTY21750PE");
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
        @DisplayName("Exit on target: P&L >= 50% of entry premium")
        void exitOnTarget() {
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            addPositionWithPnl(BigDecimal.valueOf(100), BigDecimal.ZERO);

            assertThat(strategy.callShouldExit(snapshot)).isTrue();
        }

        @Test
        @DisplayName("No exit when P&L below target")
        void noExitBelowTarget() {
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            addPositionWithPnl(BigDecimal.valueOf(80), BigDecimal.ZERO);

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
    // DELTA ROLL ADJUSTMENT
    // ========================

    @Nested
    @DisplayName("Delta Roll Adjustment")
    class DeltaRollAdjustment {

        private MarketSnapshot snapshot;

        @BeforeEach
        void setUp() {
            snapshot = MarketSnapshot.builder()
                    .spotPrice(BigDecimal.valueOf(22200))
                    .timestamp(LocalDateTime.now())
                    .build();

            strategy.arm();
            strategy.forceStatus(StrategyStatus.ACTIVE);
        }

        @Test
        @DisplayName("Records ROLL_CALL_UP when delta too positive and short call exists")
        void rollCallUpOnPositiveDelta() {
            // calculatePositionDelta sums (delta * quantity) per position.
            // Net position delta of 0.35 > threshold 0.30 => roll call up.
            // Use quantity=1 so calculated delta equals the raw delta value.
            addPositionWithDeltaAndSymbol(BigDecimal.valueOf(0.35), "NIFTY22200CE", -75);

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isEqualTo("ROLL_CALL_UP");
        }

        @Test
        @DisplayName("Records ROLL_PUT_DOWN when delta too negative and short put exists")
        void rollPutDownOnNegativeDelta() {
            // Net position delta of -0.35 < -0.30 => roll put down
            addPositionWithDeltaAndSymbol(BigDecimal.valueOf(-0.35), "NIFTY21800PE", -75);

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isEqualTo("ROLL_PUT_DOWN");
        }

        @Test
        @DisplayName("No adjustment when delta within threshold")
        void noAdjustmentWithinThreshold() {
            addPositionWithDeltaAndSymbol(BigDecimal.valueOf(0.20), "NIFTY22200CE", -75);

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isNull();
        }

        @Test
        @DisplayName("No adjustment at exact threshold boundary")
        void noAdjustmentAtExactThreshold() {
            addPositionWithDeltaAndSymbol(BigDecimal.valueOf(0.30), "NIFTY22200CE", -75);

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isNull();
        }

        @Test
        @DisplayName("No call roll when short call position is absent")
        void noRollWhenNoShortCall() {
            // Delta positive but no short call (only long call, qty > 0)
            addPositionWithDeltaAndSymbol(BigDecimal.valueOf(0.40), "NIFTY22300CE", 75);

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isNull();
        }

        @Test
        @DisplayName("No put roll when short put position is absent")
        void noRollWhenNoShortPut() {
            // Delta negative but no short put (only long put, qty > 0)
            addPositionWithDeltaAndSymbol(BigDecimal.valueOf(-0.40), "NIFTY21700PE", 75);

            strategy.callAdjust(snapshot);

            assertThat(strategy.getLastAdjustmentType()).isNull();
        }

        @Test
        @DisplayName("No adjustment when deltaRollThreshold is null")
        void noAdjustmentWhenThresholdNull() {
            IronCondorConfig noRollConfig = IronCondorConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .callOffset(BigDecimal.valueOf(200))
                    .putOffset(BigDecimal.valueOf(200))
                    .wingWidth(BigDecimal.valueOf(100))
                    .deltaRollThreshold(null)
                    .build();

            TestableIronCondorStrategy noRollStrategy =
                    new TestableIronCondorStrategy("STR-IC-NR", "No-Roll", noRollConfig);
            noRollStrategy.arm();
            noRollStrategy.forceStatus(StrategyStatus.ACTIVE);

            addPositionWithDeltaAndSymbol(noRollStrategy, BigDecimal.valueOf(0.50), "NIFTY22200CE", -75);

            noRollStrategy.callAdjust(snapshot);

            assertThat(noRollStrategy.getLastAdjustmentType()).isNull();
        }
    }

    // ========================
    // SUPPORTED MORPHS
    // ========================

    @Nested
    @DisplayName("Supported Morphs")
    class SupportedMorphs {

        @Test
        @DisplayName("Can morph to IRON_BUTTERFLY and STRANGLE")
        void supportedMorphTypes() {
            List<StrategyType> morphs = strategy.supportedMorphs();

            assertThat(morphs).containsExactlyInAnyOrder(StrategyType.IRON_BUTTERFLY, StrategyType.STRANGLE);
        }
    }

    // ========================
    // HELPERS
    // ========================

    private void addPositionWithPnl(BigDecimal unrealizedPnl, BigDecimal realizedPnl) {
        Position position = Position.builder()
                .id("POS-" + System.nanoTime())
                .tradingSymbol("NIFTY22200CE")
                .quantity(-75)
                .unrealizedPnl(unrealizedPnl)
                .realizedPnl(realizedPnl)
                .lastUpdated(LocalDateTime.now())
                .build();
        strategy.addPosition(position);
    }

    /**
     * Adds positions to produce desired net delta while maintaining short position detection:
     * 1. If positionQuantity < 0: a short position (no greeks) so getShortCall/getShortPut finds it
     * 2. A delta-driver position (qty=1) so calculatePositionDelta returns the desired net delta
     */
    private void addPositionWithDeltaAndSymbol(BigDecimal netDelta, String symbol, int positionQuantity) {
        addPositionWithDeltaAndSymbol(strategy, netDelta, symbol, positionQuantity);
    }

    private void addPositionWithDeltaAndSymbol(
            TestableIronCondorStrategy targetStrategy, BigDecimal netDelta, String symbol, int positionQuantity) {
        if (positionQuantity < 0) {
            // Short position for getShortCall/getShortPut detection (no greeks = excluded from delta calc)
            Position shortPosition = Position.builder()
                    .id("POS-SHORT-" + System.nanoTime())
                    .tradingSymbol(symbol)
                    .quantity(positionQuantity)
                    .greeks(Greeks.UNAVAILABLE)
                    .unrealizedPnl(BigDecimal.ZERO)
                    .realizedPnl(BigDecimal.ZERO)
                    .lastUpdated(LocalDateTime.now())
                    .build();
            targetStrategy.addPosition(shortPosition);
        }

        // Delta-driver position: qty=1 so calculatePositionDelta returns netDelta * 1 = netDelta
        Greeks greeks = Greeks.builder()
                .delta(netDelta)
                .gamma(BigDecimal.ZERO)
                .theta(BigDecimal.ZERO)
                .vega(BigDecimal.ZERO)
                .iv(BigDecimal.valueOf(15))
                .build();

        Position deltaPosition = Position.builder()
                .id("POS-DELTA-" + System.nanoTime())
                .tradingSymbol(symbol + "-DELTA")
                .quantity(1)
                .greeks(greeks)
                .unrealizedPnl(BigDecimal.ZERO)
                .realizedPnl(BigDecimal.ZERO)
                .lastUpdated(LocalDateTime.now())
                .build();
        targetStrategy.addPosition(deltaPosition);
    }

    // ========================
    // TESTABLE SUBCLASS
    // ========================

    /**
     * Exposes protected methods and tracks adjustment types for assertions.
     */
    static class TestableIronCondorStrategy extends IronCondorStrategy {

        private Boolean withinEntryWindowOverride;
        private String lastAdjustmentType;

        TestableIronCondorStrategy(String id, String name, IronCondorConfig config) {
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
