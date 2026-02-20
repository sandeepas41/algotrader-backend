package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Greeks;
import com.algotrader.domain.model.Instrument;
import com.algotrader.domain.model.NewLegDefinition;
import com.algotrader.domain.model.Position;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.oms.JournaledMultiLegExecutor;
import com.algotrader.oms.OrderRequest;
import com.algotrader.service.InstrumentService;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.BaseStrategyConfig;
import com.algotrader.strategy.base.MarketSnapshot;
import com.algotrader.strategy.base.PositionalStrategyConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for BaseStrategy abstract class, using a concrete TestStrategy implementation.
 * Covers evaluation loop, lifecycle transitions, timing guards, exit conditions,
 * position tracking, auto-pause thresholds, and StampedLock concurrency.
 */
class BaseStrategyTest {

    private EventPublisherHelper eventPublisherHelper;
    private JournaledMultiLegExecutor journaledMultiLegExecutor;
    private InstrumentService instrumentService;
    private TestStrategy strategy;
    private PositionalStrategyConfig config;

    @BeforeEach
    void setUp() {
        eventPublisherHelper = mock(EventPublisherHelper.class);
        journaledMultiLegExecutor = mock(JournaledMultiLegExecutor.class);
        instrumentService = mock(InstrumentService.class);

        // Default: entry execution succeeds (strategies transition to ACTIVE)
        JournaledMultiLegExecutor.MultiLegResult successResult = JournaledMultiLegExecutor.MultiLegResult.builder()
                .groupId("test-group")
                .success(true)
                .legResults(List.of())
                .build();
        when(journaledMultiLegExecutor.executeParallel(any(), anyString(), anyString(), any()))
                .thenReturn(successResult);
        // Close/exit uses buy-first-then-sell (buys back shorts first to free margin)
        when(journaledMultiLegExecutor.executeBuyFirstThenSell(
                        any(), anyString(), anyString(), any(), any(Duration.class)))
                .thenReturn(successResult);

        config = PositionalStrategyConfig.builder()
                .underlying("NIFTY")
                .lots(1)
                .strikeInterval(BigDecimal.valueOf(50))
                .entryStartTime(LocalTime.of(9, 15))
                .entryEndTime(LocalTime.of(15, 15))
                .targetPercent(BigDecimal.valueOf(0.5))
                .stopLossMultiplier(BigDecimal.valueOf(2.0))
                .minDaysToExpiry(1)
                .build();

        strategy = new TestStrategy("STR-1", "Test Straddle", config);
        strategy.setServices(eventPublisherHelper, journaledMultiLegExecutor, instrumentService);
    }

    private MarketSnapshot snapshot() {
        return MarketSnapshot.builder()
                .spotPrice(BigDecimal.valueOf(22000))
                .atmIV(BigDecimal.valueOf(0.15))
                .timestamp(LocalDateTime.now())
                .build();
    }

    private Position buildPosition(String id, String symbol, int quantity, BigDecimal unrealizedPnl) {
        return Position.builder()
                .id(id)
                .tradingSymbol(symbol)
                .instrumentToken(256265L)
                .quantity(quantity)
                .unrealizedPnl(unrealizedPnl)
                .realizedPnl(BigDecimal.ZERO)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private Position buildPositionWithGreeks(
            String id, String symbol, int quantity, BigDecimal unrealizedPnl, BigDecimal delta) {
        return Position.builder()
                .id(id)
                .tradingSymbol(symbol)
                .instrumentToken(256265L)
                .quantity(quantity)
                .unrealizedPnl(unrealizedPnl)
                .realizedPnl(BigDecimal.ZERO)
                .lastUpdated(LocalDateTime.now())
                .greeks(Greeks.builder()
                        .delta(delta)
                        .gamma(BigDecimal.ZERO)
                        .theta(BigDecimal.ZERO)
                        .vega(BigDecimal.ZERO)
                        .rho(BigDecimal.ZERO)
                        .iv(BigDecimal.valueOf(0.15))
                        .calculatedAt(LocalDateTime.now())
                        .build())
                .build();
    }

    // ========================
    // LIFECYCLE TRANSITIONS
    // ========================

    @Nested
    @DisplayName("Lifecycle Transitions")
    class LifecycleTransitions {

        @Test
        @DisplayName("Initial status is CREATED")
        void initialStatusIsCreated() {
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.CREATED);
        }

        @Test
        @DisplayName("arm() transitions to ARMED")
        void armTransitionsToArmed() {
            strategy.arm();
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.ARMED);
        }

        @Test
        @DisplayName("pause() transitions to PAUSED")
        void pauseTransitionsToPaused() {
            strategy.arm();
            strategy.pause();
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.PAUSED);
        }

        @Test
        @DisplayName("resume() transitions to ACTIVE")
        void resumeTransitionsToActive() {
            strategy.arm();
            strategy.pause();
            strategy.resume();
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.ACTIVE);
        }

        @Test
        @DisplayName("initiateClose() transitions to CLOSING")
        void initiateCloseTransitionsToClosing() {
            strategy.arm();
            strategy.initiateClose();
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.CLOSING);
        }

        @Test
        @DisplayName("initiateClose builds and executes exit orders via executor")
        void initiateCloseExecutesExitOrders() {
            strategy.arm();
            strategy.setShouldEnter(true);
            strategy.evaluate(snapshot()); // -> ACTIVE

            // Add a position so exit orders are built
            strategy.addPosition(buildPosition("P1", "NIFTY24FEB22000CE", -50, BigDecimal.ZERO));

            strategy.initiateClose();

            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.CLOSING);
            verify(journaledMultiLegExecutor)
                    .executeBuyFirstThenSell(
                            any(), eq("STR-1"), eq("EXIT"), eq(OrderPriority.STRATEGY_EXIT), any(Duration.class));
        }

        @Test
        @DisplayName("Lifecycle changes publish decision events")
        void lifecyclePublishesDecisionEvents() {
            strategy.arm();
            strategy.pause();
            strategy.resume();
            strategy.initiateClose();

            // arm, pause, resume, initiateClose = 4 decision events
            verify(eventPublisherHelper, times(4))
                    .publishDecision(any(), anyString(), anyString(), eq("STR-1"), any(Map.class));
        }
    }

    // ========================
    // EVALUATION LOOP
    // ========================

    @Nested
    @DisplayName("Evaluation Loop")
    class EvaluationLoop {

        @Test
        @DisplayName("CREATED status does not trigger any evaluation")
        void createdStatusDoesNotEvaluate() {
            strategy.evaluate(snapshot());

            assertThat(strategy.shouldEnterCallCount.get()).isZero();
            assertThat(strategy.shouldExitCallCount.get()).isZero();
        }

        @Test
        @DisplayName("ARMED status calls shouldEnter")
        void armedCallsShouldEnter() {
            strategy.arm();
            strategy.evaluate(snapshot());

            assertThat(strategy.shouldEnterCallCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("ACTIVE status calls shouldExit")
        void activeCallsShouldExit() {
            strategy.arm();
            strategy.setShouldEnter(true);
            strategy.evaluate(snapshot()); // triggers entry -> ACTIVE

            // Reset and evaluate again
            strategy.shouldExitCallCount.set(0);
            strategy.forceLastEvaluationToNull(); // bypass interval check
            strategy.evaluate(snapshot());

            assertThat(strategy.shouldExitCallCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("ACTIVE status calls adjust when not in cooldown")
        void activeCallsAdjust() {
            strategy.arm();
            strategy.setShouldEnter(true);
            strategy.evaluate(snapshot()); // entry -> ACTIVE

            strategy.adjustCallCount.set(0);
            strategy.forceLastEvaluationToNull();
            strategy.evaluate(snapshot());

            assertThat(strategy.adjustCallCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Entry success transitions to ACTIVE and sets entryTime")
        void entrySuccessTransitionsToActive() {
            strategy.arm();
            strategy.setShouldEnter(true);
            strategy.setEntryOrders(List.of(OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .instrumentToken(256265L)
                    .quantity(50)
                    .build()));

            strategy.evaluate(snapshot());

            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.ACTIVE);
            assertThat(strategy.getEntryTime()).isNotNull();
        }

        @Test
        @DisplayName("Entry stays ARMED when executor returns failure")
        void entryStaysArmedOnExecutionFailure() {
            when(journaledMultiLegExecutor.executeParallel(any(), anyString(), anyString(), any()))
                    .thenReturn(JournaledMultiLegExecutor.MultiLegResult.builder()
                            .groupId("fail-group")
                            .success(false)
                            .legResults(List.of())
                            .build());

            strategy.arm();
            strategy.setShouldEnter(true);
            strategy.evaluate(snapshot());

            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.ARMED);
        }

        @Test
        @DisplayName("Entry with empty orders does not transition to ACTIVE")
        void emptyEntryOrdersStaysArmed() {
            strategy.arm();
            strategy.setShouldEnter(true);
            strategy.setEntryOrders(List.of()); // no orders

            strategy.evaluate(snapshot());

            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.ARMED);
        }

        @Test
        @DisplayName("Exit triggers initiateClose")
        void exitTriggersClose() {
            strategy.arm();
            strategy.setShouldEnter(true);
            strategy.evaluate(snapshot()); // -> ACTIVE

            strategy.setShouldExit(true);
            strategy.forceLastEvaluationToNull();
            strategy.evaluate(snapshot());

            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.CLOSING);
        }

        @Test
        @DisplayName("PAUSED status does not trigger evaluation")
        void pausedDoesNotEvaluate() {
            strategy.arm();
            strategy.pause();
            strategy.forceLastEvaluationToNull();
            strategy.shouldEnterCallCount.set(0);

            strategy.evaluate(snapshot());

            assertThat(strategy.shouldEnterCallCount.get()).isZero();
            assertThat(strategy.shouldExitCallCount.get()).isZero();
        }

        @Test
        @DisplayName("CLOSING status does not trigger evaluation")
        void closingDoesNotEvaluate() {
            strategy.arm();
            strategy.initiateClose();
            strategy.forceLastEvaluationToNull();
            strategy.shouldEnterCallCount.set(0);

            strategy.evaluate(snapshot());

            assertThat(strategy.shouldEnterCallCount.get()).isZero();
        }
    }

    // ========================
    // MONITORING INTERVAL
    // ========================

    @Nested
    @DisplayName("Monitoring Interval")
    class MonitoringInterval {

        @Test
        @DisplayName("First evaluation always runs (no lastEvaluationTime)")
        void firstEvaluationAlwaysRuns() {
            strategy.arm();
            strategy.evaluate(snapshot());

            assertThat(strategy.shouldEnterCallCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Skips evaluation when interval has not elapsed")
        void skipsWhenIntervalNotElapsed() {
            strategy.arm();
            strategy.evaluate(snapshot()); // first eval sets lastEvaluationTime
            strategy.shouldEnterCallCount.set(0);

            // Second eval immediately should be skipped (5 min interval)
            strategy.evaluate(snapshot());

            assertThat(strategy.shouldEnterCallCount.get()).isZero();
        }

        @Test
        @DisplayName("Runs evaluation after interval has elapsed")
        void runsAfterIntervalElapsed() {
            strategy.arm();
            strategy.evaluate(snapshot()); // first eval
            strategy.shouldEnterCallCount.set(0);

            // Force lastEvaluationTime to 6 minutes ago
            strategy.forceLastEvaluationTime(LocalDateTime.now().minusMinutes(6));
            strategy.evaluate(snapshot());

            assertThat(strategy.shouldEnterCallCount.get()).isEqualTo(1);
        }
    }

    // ========================
    // STALE DATA GUARD
    // ========================

    @Nested
    @DisplayName("Stale Data Guard")
    class StaleDataGuard {

        @Test
        @DisplayName("Skips evaluation when position data is stale")
        void skipsWhenDataIsStale() {
            strategy.arm();
            strategy.setShouldEnter(true);
            strategy.evaluate(snapshot()); // -> ACTIVE

            // Add a position with old lastUpdated (10 seconds ago, threshold is 5s)
            strategy.addPosition(Position.builder()
                    .id("P1")
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .instrumentToken(256265L)
                    .quantity(-50)
                    .unrealizedPnl(BigDecimal.ZERO)
                    .lastUpdated(LocalDateTime.now().minusSeconds(10))
                    .build());

            strategy.forceLastEvaluationToNull();
            strategy.shouldExitCallCount.set(0);
            strategy.evaluate(snapshot());

            // shouldExit should NOT be called because data is stale
            assertThat(strategy.shouldExitCallCount.get()).isZero();
        }

        @Test
        @DisplayName("Fresh data passes stale data guard")
        void freshDataPassesGuard() {
            strategy.arm();
            strategy.setShouldEnter(true);
            strategy.evaluate(snapshot()); // -> ACTIVE

            // Add a position with fresh lastUpdated
            strategy.addPosition(Position.builder()
                    .id("P1")
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .instrumentToken(256265L)
                    .quantity(-50)
                    .unrealizedPnl(BigDecimal.ZERO)
                    .lastUpdated(LocalDateTime.now())
                    .build());

            strategy.forceLastEvaluationToNull();
            strategy.shouldExitCallCount.set(0);
            strategy.evaluate(snapshot());

            assertThat(strategy.shouldExitCallCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Position with null lastUpdated is treated as stale")
        void nullLastUpdatedIsTreatedAsStale() {
            strategy.arm();
            strategy.setShouldEnter(true);
            strategy.evaluate(snapshot()); // -> ACTIVE

            strategy.addPosition(Position.builder()
                    .id("P1")
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .instrumentToken(256265L)
                    .quantity(-50)
                    .lastUpdated(null) // null = stale
                    .build());

            strategy.forceLastEvaluationToNull();
            strategy.shouldExitCallCount.set(0);
            strategy.evaluate(snapshot());

            assertThat(strategy.shouldExitCallCount.get()).isZero();
        }

        @Test
        @DisplayName("ARMED state with no positions does not trigger stale guard")
        void armedWithNoPositionsNotStale() {
            strategy.arm();
            // No positions -> isDataStale() returns false
            strategy.evaluate(snapshot());

            assertThat(strategy.shouldEnterCallCount.get()).isEqualTo(1);
        }
    }

    // ========================
    // ADJUSTMENT COOLDOWN
    // ========================

    @Nested
    @DisplayName("Adjustment Cooldown")
    class AdjustmentCooldown {

        @Test
        @DisplayName("Adjustment blocked when in cooldown")
        void adjustmentBlockedInCooldown() {
            strategy.arm();
            strategy.setShouldEnter(true);
            strategy.evaluate(snapshot()); // -> ACTIVE

            // Record an adjustment (starts cooldown)
            strategy.callRecordAdjustment("SHIFT_STRIKE");

            strategy.adjustCallCount.set(0);
            strategy.forceLastEvaluationToNull();
            strategy.evaluate(snapshot());

            // adjust() should NOT be called due to cooldown
            assertThat(strategy.adjustCallCount.get()).isZero();
        }

        @Test
        @DisplayName("Adjustment allowed after cooldown expires")
        void adjustmentAllowedAfterCooldown() {
            strategy.arm();
            strategy.setShouldEnter(true);
            strategy.evaluate(snapshot()); // -> ACTIVE

            // Force last adjustment to 6 minutes ago (cooldown is 5 min)
            strategy.forceLastAdjustmentTime(LocalDateTime.now().minusMinutes(6));

            strategy.adjustCallCount.set(0);
            strategy.forceLastEvaluationToNull();
            strategy.evaluate(snapshot());

            assertThat(strategy.adjustCallCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("First evaluation after entry has no cooldown (lastAdjustmentTime is null)")
        void firstEvaluationNoCooldown() {
            strategy.arm();
            strategy.setShouldEnter(true);
            strategy.evaluate(snapshot()); // -> ACTIVE

            strategy.adjustCallCount.set(0);
            strategy.forceLastEvaluationToNull();
            strategy.evaluate(snapshot());

            // No previous adjustment -> no cooldown -> adjust() called
            assertThat(strategy.adjustCallCount.get()).isEqualTo(1);
        }
    }

    // ========================
    // EXIT CONDITION HELPERS
    // ========================

    @Nested
    @DisplayName("Exit Condition Helpers")
    class ExitConditionHelpers {

        @Test
        @DisplayName("isTargetReached returns true when P&L >= target")
        void targetReachedWhenPnlAboveTarget() {
            // entryPremium = 200, targetPercent = 0.5, so target = 100
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            strategy.addPosition(buildPosition("P1", "NIFTY24FEB22000CE", -50, BigDecimal.valueOf(120)));

            assertThat(strategy.callIsTargetReached(BigDecimal.valueOf(0.5))).isTrue();
        }

        @Test
        @DisplayName("isTargetReached returns false when P&L < target")
        void targetNotReachedWhenPnlBelowTarget() {
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            strategy.addPosition(buildPosition("P1", "NIFTY24FEB22000CE", -50, BigDecimal.valueOf(80)));

            assertThat(strategy.callIsTargetReached(BigDecimal.valueOf(0.5))).isFalse();
        }

        @Test
        @DisplayName("isTargetReached returns false when entryPremium is null")
        void targetReturnsFalseWithNullPremium() {
            assertThat(strategy.callIsTargetReached(BigDecimal.valueOf(0.5))).isFalse();
        }

        @Test
        @DisplayName("isStopLossHit returns true when loss exceeds multiplier")
        void stopLossHitWhenLossExceedsMultiplier() {
            // entryPremium = 200, stopLossMultiplier = 2.0, so stopLoss = -400
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            strategy.addPosition(buildPosition("P1", "NIFTY24FEB22000CE", -50, BigDecimal.valueOf(-500)));

            assertThat(strategy.callIsStopLossHit(BigDecimal.valueOf(2.0))).isTrue();
        }

        @Test
        @DisplayName("isStopLossHit returns false when loss is below multiplier")
        void stopLossNotHitWhenLossBelow() {
            strategy.forceEntryPremium(BigDecimal.valueOf(200));
            strategy.addPosition(buildPosition("P1", "NIFTY24FEB22000CE", -50, BigDecimal.valueOf(-100)));

            assertThat(strategy.callIsStopLossHit(BigDecimal.valueOf(2.0))).isFalse();
        }

        @Test
        @DisplayName("isStopLossHit returns false when entryPremium is null")
        void stopLossReturnsFalseWithNullPremium() {
            assertThat(strategy.callIsStopLossHit(BigDecimal.valueOf(2.0))).isFalse();
        }
    }

    // ========================
    // P&L CALCULATIONS
    // ========================

    @Nested
    @DisplayName("P&L Calculations")
    class PnlCalculations {

        @Test
        @DisplayName("calculateTotalPnl sums unrealized and realized P&L across positions")
        void totalPnlSumsAllPositions() {
            strategy.addPosition(buildPosition("P1", "CE", -50, BigDecimal.valueOf(500)));
            strategy.addPosition(buildPosition("P2", "PE", -50, BigDecimal.valueOf(-200)));

            assertThat(strategy.calculateTotalPnl()).isEqualByComparingTo(BigDecimal.valueOf(300));
        }

        @Test
        @DisplayName("calculateTotalPnl returns zero with no positions")
        void totalPnlZeroWithNoPositions() {
            assertThat(strategy.calculateTotalPnl()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("calculateTotalPnl handles null unrealized and realized P&L")
        void totalPnlHandlesNulls() {
            strategy.addPosition(Position.builder()
                    .id("P1")
                    .tradingSymbol("CE")
                    .instrumentToken(256265L)
                    .quantity(-50)
                    .unrealizedPnl(null)
                    .realizedPnl(null)
                    .lastUpdated(LocalDateTime.now())
                    .build());

            assertThat(strategy.calculateTotalPnl()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("calculatePositionDelta sums delta * quantity across positions with available greeks")
        void positionDeltaSumsCorrectly() {
            // Short call: quantity=-50, delta=0.5 -> contribution = -25
            // Short put: quantity=-50, delta=-0.4 -> contribution = 20
            // Net delta = -5
            strategy.addPosition(
                    buildPositionWithGreeks("P1", "NIFTY24FEB22000CE", -50, BigDecimal.ZERO, BigDecimal.valueOf(0.5)));
            strategy.addPosition(
                    buildPositionWithGreeks("P2", "NIFTY24FEB21800PE", -50, BigDecimal.ZERO, BigDecimal.valueOf(-0.4)));

            assertThat(strategy.callCalculatePositionDelta()).isEqualByComparingTo(BigDecimal.valueOf(-5.0));
        }
    }

    // ========================
    // POSITION TRACKING
    // ========================

    @Nested
    @DisplayName("Position Tracking")
    class PositionTracking {

        @Test
        @DisplayName("addPosition adds to the list")
        void addPositionAddsToList() {
            Position position = buildPosition("P1", "NIFTY24FEB22000CE", -50, BigDecimal.ZERO);
            strategy.addPosition(position);

            assertThat(strategy.getPositions()).hasSize(1);
            assertThat(strategy.getPositions().get(0).getId()).isEqualTo("P1");
        }

        @Test
        @DisplayName("updatePosition replaces matching position")
        void updatePositionReplacesMatch() {
            strategy.addPosition(buildPosition("P1", "NIFTY24FEB22000CE", -50, BigDecimal.ZERO));

            Position updated = buildPosition("P1", "NIFTY24FEB22000CE", -50, BigDecimal.valueOf(1000));
            strategy.updatePosition(updated);

            assertThat(strategy.getPositions()).hasSize(1);
            assertThat(strategy.getPositions().get(0).getUnrealizedPnl())
                    .isEqualByComparingTo(BigDecimal.valueOf(1000));
        }

        @Test
        @DisplayName("removePosition removes matching position")
        void removePositionRemovesMatch() {
            strategy.addPosition(buildPosition("P1", "NIFTY24FEB22000CE", -50, BigDecimal.ZERO));
            strategy.addPosition(buildPosition("P2", "NIFTY24FEB21800PE", -50, BigDecimal.ZERO));

            strategy.removePosition("P1");

            assertThat(strategy.getPositions()).hasSize(1);
            assertThat(strategy.getPositions().get(0).getId()).isEqualTo("P2");
        }

        @Test
        @DisplayName("getPositions returns defensive copy")
        void getPositionsReturnsDefensiveCopy() {
            strategy.addPosition(buildPosition("P1", "NIFTY24FEB22000CE", -50, BigDecimal.ZERO));

            List<Position> copy = strategy.getPositions();
            assertThat(copy).hasSize(1);

            // Modifying the copy should not affect the internal list
            try {
                copy.add(buildPosition("P2", "NIFTY24FEB21800PE", -50, BigDecimal.ZERO));
            } catch (UnsupportedOperationException e) {
                // List.copyOf returns unmodifiable, this is expected
            }
            assertThat(strategy.getPositions()).hasSize(1);
        }
    }

    // ========================
    // POSITION HELPERS
    // ========================

    @Nested
    @DisplayName("Position Helpers")
    class PositionHelpers {

        @Test
        @DisplayName("getShortCall finds short CE position")
        void getShortCallFindsShortCE() {
            strategy.addPosition(buildPosition("P1", "NIFTY24FEB22000CE", -50, BigDecimal.ZERO));
            strategy.addPosition(buildPosition("P2", "NIFTY24FEB21800PE", -50, BigDecimal.ZERO));

            Position shortCall = strategy.callGetShortCall();
            assertThat(shortCall).isNotNull();
            assertThat(shortCall.getTradingSymbol()).endsWith("CE");
        }

        @Test
        @DisplayName("getShortPut finds short PE position")
        void getShortPutFindsShortPE() {
            strategy.addPosition(buildPosition("P1", "NIFTY24FEB22000CE", -50, BigDecimal.ZERO));
            strategy.addPosition(buildPosition("P2", "NIFTY24FEB21800PE", -50, BigDecimal.ZERO));

            Position shortPut = strategy.callGetShortPut();
            assertThat(shortPut).isNotNull();
            assertThat(shortPut.getTradingSymbol()).endsWith("PE");
        }

        @Test
        @DisplayName("getShortCall returns null when no short CE exists")
        void getShortCallReturnsNullWhenNone() {
            strategy.addPosition(buildPosition("P1", "NIFTY24FEB22000CE", 50, BigDecimal.ZERO)); // long, not short

            assertThat(strategy.callGetShortCall()).isNull();
        }
    }

    // ========================
    // AUTO-PAUSE THRESHOLDS
    // ========================

    @Nested
    @DisplayName("Auto-Pause Thresholds")
    class AutoPauseThresholds {

        @Test
        @DisplayName("Auto-pauses when P&L drops below threshold")
        void autoPausesOnPnlThreshold() {
            PositionalStrategyConfig configWithAutoPause = PositionalStrategyConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(2.0))
                    .minDaysToExpiry(1)
                    .autoPausePnlThreshold(BigDecimal.valueOf(-15000))
                    .build();

            TestStrategy autoStrategy = new TestStrategy("STR-AP", "Auto-Pause Test", configWithAutoPause);
            autoStrategy.setServices(eventPublisherHelper, journaledMultiLegExecutor, instrumentService);

            // Get to ACTIVE
            autoStrategy.arm();
            autoStrategy.setShouldEnter(true);
            autoStrategy.evaluate(snapshot());

            // Add position with loss exceeding threshold
            autoStrategy.addPosition(buildPosition("P1", "CE", -50, BigDecimal.valueOf(-20000)));

            autoStrategy.forceLastEvaluationToNull();
            autoStrategy.evaluate(snapshot());

            assertThat(autoStrategy.getStatus()).isEqualTo(StrategyStatus.PAUSED);
        }

        @Test
        @DisplayName("Auto-pauses when delta exceeds threshold")
        void autoPausesOnDeltaThreshold() {
            PositionalStrategyConfig configWithDeltaPause = PositionalStrategyConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(2.0))
                    .minDaysToExpiry(1)
                    .autoPauseDeltaThreshold(BigDecimal.valueOf(0.5))
                    .build();

            TestStrategy deltaStrategy = new TestStrategy("STR-DP", "Delta Pause Test", configWithDeltaPause);
            deltaStrategy.setServices(eventPublisherHelper, journaledMultiLegExecutor, instrumentService);

            // Get to ACTIVE
            deltaStrategy.arm();
            deltaStrategy.setShouldEnter(true);
            deltaStrategy.evaluate(snapshot());

            // Add position with high delta (net delta > 0.5)
            deltaStrategy.addPosition(
                    buildPositionWithGreeks("P1", "NIFTY24FEB22000CE", 100, BigDecimal.ZERO, BigDecimal.valueOf(0.8)));

            deltaStrategy.forceLastEvaluationToNull();
            deltaStrategy.evaluate(snapshot());

            assertThat(deltaStrategy.getStatus()).isEqualTo(StrategyStatus.PAUSED);
        }

        @Test
        @DisplayName("Does not auto-pause when thresholds are null (disabled)")
        void noAutoPauseWhenThresholdsNull() {
            // Default config has null thresholds
            strategy.arm();
            strategy.setShouldEnter(true);
            strategy.evaluate(snapshot()); // -> ACTIVE

            strategy.addPosition(buildPosition("P1", "CE", -50, BigDecimal.valueOf(-50000)));
            strategy.forceLastEvaluationToNull();
            strategy.evaluate(snapshot());

            // Should still be ACTIVE (no auto-pause thresholds configured)
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.ACTIVE);
        }

        @Test
        @DisplayName("Does not auto-pause when P&L above threshold")
        void noAutoPauseWhenPnlAboveThreshold() {
            PositionalStrategyConfig configWithAutoPause = PositionalStrategyConfig.builder()
                    .underlying("NIFTY")
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(2.0))
                    .minDaysToExpiry(1)
                    .autoPausePnlThreshold(BigDecimal.valueOf(-15000))
                    .build();

            TestStrategy safeStrategy = new TestStrategy("STR-SAFE", "Safe Test", configWithAutoPause);
            safeStrategy.setServices(eventPublisherHelper, journaledMultiLegExecutor, instrumentService);

            safeStrategy.arm();
            safeStrategy.setShouldEnter(true);
            safeStrategy.evaluate(snapshot()); // -> ACTIVE

            // P&L = -5000, threshold = -15000, so no auto-pause
            safeStrategy.addPosition(buildPosition("P1", "CE", -50, BigDecimal.valueOf(-5000)));
            safeStrategy.forceLastEvaluationToNull();
            safeStrategy.evaluate(snapshot());

            assertThat(safeStrategy.getStatus()).isEqualTo(StrategyStatus.ACTIVE);
        }
    }

    // ========================
    // IMMEDIATE ENTRY
    // ========================

    @Nested
    @DisplayName("Immediate Entry (from FE leg configs)")
    class ImmediateEntry {

        @Test
        @DisplayName("executeImmediateEntry transitions to ACTIVE on success")
        void immediateEntryTransitionsToActive() {
            // Build config with fixed legs + immediate entry
            PositionalStrategyConfig immediateConfig = PositionalStrategyConfig.builder()
                    .underlying("NIFTY")
                    .expiry(LocalDate.of(2026, 2, 17))
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(2.0))
                    .minDaysToExpiry(1)
                    .immediateEntry(true)
                    .legConfigs(List.of(
                            NewLegDefinition.builder()
                                    .strike(BigDecimal.valueOf(25850))
                                    .optionType(InstrumentType.CE)
                                    .side(OrderSide.BUY)
                                    .lots(1)
                                    .build(),
                            NewLegDefinition.builder()
                                    .strike(BigDecimal.valueOf(26050))
                                    .optionType(InstrumentType.CE)
                                    .side(OrderSide.SELL)
                                    .lots(1)
                                    .build()))
                    .build();

            TestStrategy immediateStrategy = new TestStrategy("STR-IMM", "Immediate Test", immediateConfig);
            immediateStrategy.setServices(eventPublisherHelper, journaledMultiLegExecutor, instrumentService);

            // Mock instrument resolution
            Instrument ceInstrument1 = Instrument.builder()
                    .token(100001L)
                    .tradingSymbol("NIFTY26FEB25850CE")
                    .exchange("NFO")
                    .lotSize(75)
                    .build();
            Instrument ceInstrument2 = Instrument.builder()
                    .token(100002L)
                    .tradingSymbol("NIFTY26FEB26050CE")
                    .exchange("NFO")
                    .lotSize(75)
                    .build();

            when(instrumentService.resolveOption(
                            eq("NIFTY"),
                            eq(LocalDate.of(2026, 2, 17)),
                            eq(BigDecimal.valueOf(25850)),
                            eq(InstrumentType.CE)))
                    .thenReturn(Optional.of(ceInstrument1));
            when(instrumentService.resolveOption(
                            eq("NIFTY"),
                            eq(LocalDate.of(2026, 2, 17)),
                            eq(BigDecimal.valueOf(26050)),
                            eq(InstrumentType.CE)))
                    .thenReturn(Optional.of(ceInstrument2));

            immediateStrategy.arm();
            immediateStrategy.executeImmediateEntry();

            assertThat(immediateStrategy.getStatus()).isEqualTo(StrategyStatus.ACTIVE);
            verify(journaledMultiLegExecutor)
                    .executeParallel(any(), eq("STR-IMM"), eq("ENTRY"), eq(OrderPriority.STRATEGY_ENTRY));
        }

        @Test
        @DisplayName("executeImmediateEntry stays ARMED when executor fails")
        void immediateEntryStaysArmedOnFailure() {
            PositionalStrategyConfig immediateConfig = PositionalStrategyConfig.builder()
                    .underlying("NIFTY")
                    .expiry(LocalDate.of(2026, 2, 17))
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(2.0))
                    .minDaysToExpiry(1)
                    .immediateEntry(true)
                    .legConfigs(List.of(NewLegDefinition.builder()
                            .strike(BigDecimal.valueOf(25850))
                            .optionType(InstrumentType.CE)
                            .side(OrderSide.BUY)
                            .lots(1)
                            .build()))
                    .build();

            TestStrategy immediateStrategy = new TestStrategy("STR-FAIL", "Fail Test", immediateConfig);
            immediateStrategy.setServices(eventPublisherHelper, journaledMultiLegExecutor, instrumentService);

            // Mock instrument resolution
            when(instrumentService.resolveOption(any(), any(), any(), any()))
                    .thenReturn(Optional.of(Instrument.builder()
                            .token(100001L)
                            .tradingSymbol("NIFTY26FEB25850CE")
                            .exchange("NFO")
                            .lotSize(75)
                            .build()));

            // Mock executor failure
            when(journaledMultiLegExecutor.executeParallel(any(), anyString(), anyString(), any()))
                    .thenReturn(JournaledMultiLegExecutor.MultiLegResult.builder()
                            .groupId("fail-group")
                            .success(false)
                            .legResults(List.of())
                            .build());

            immediateStrategy.arm();
            immediateStrategy.executeImmediateEntry();

            assertThat(immediateStrategy.getStatus()).isEqualTo(StrategyStatus.ARMED);
        }

        @Test
        @DisplayName("executeImmediateEntry skips when instrument cannot be resolved")
        void immediateEntrySkipsOnUnresolvedInstrument() {
            PositionalStrategyConfig immediateConfig = PositionalStrategyConfig.builder()
                    .underlying("NIFTY")
                    .expiry(LocalDate.of(2026, 2, 17))
                    .lots(1)
                    .strikeInterval(BigDecimal.valueOf(50))
                    .targetPercent(BigDecimal.valueOf(0.5))
                    .stopLossMultiplier(BigDecimal.valueOf(2.0))
                    .minDaysToExpiry(1)
                    .immediateEntry(true)
                    .legConfigs(List.of(NewLegDefinition.builder()
                            .strike(BigDecimal.valueOf(99999))
                            .optionType(InstrumentType.CE)
                            .side(OrderSide.BUY)
                            .lots(1)
                            .build()))
                    .build();

            TestStrategy immediateStrategy = new TestStrategy("STR-NR", "No Resolve Test", immediateConfig);
            immediateStrategy.setServices(eventPublisherHelper, journaledMultiLegExecutor, instrumentService);

            // Mock instrument NOT found
            when(instrumentService.resolveOption(any(), any(), any(), any())).thenReturn(Optional.empty());

            immediateStrategy.arm();
            immediateStrategy.executeImmediateEntry();

            // Should stay ARMED — no orders were submitted
            assertThat(immediateStrategy.getStatus()).isEqualTo(StrategyStatus.ARMED);
            verify(journaledMultiLegExecutor, times(0)).executeParallel(any(), anyString(), anyString(), any());
        }
    }

    // ========================
    // ROUND TO STRIKE
    // ========================

    @Nested
    @DisplayName("Round to Strike")
    class RoundToStrike {

        @Test
        @DisplayName("Rounds price up to nearest strike interval")
        void roundsUpToNearestInterval() {
            // 22035 with interval 50 -> 22050
            BigDecimal result = strategy.callRoundToStrike(BigDecimal.valueOf(22035));
            assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(22050));
        }

        @Test
        @DisplayName("Rounds price down to nearest strike interval")
        void roundsDownToNearestInterval() {
            // 22010 with interval 50 -> 22000
            BigDecimal result = strategy.callRoundToStrike(BigDecimal.valueOf(22010));
            assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(22000));
        }

        @Test
        @DisplayName("Exact strike returns same value")
        void exactStrikeReturnsSame() {
            BigDecimal result = strategy.callRoundToStrike(BigDecimal.valueOf(22000));
            assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(22000));
        }
    }

    // ========================
    // IDENTITY & CONFIG
    // ========================

    @Nested
    @DisplayName("Identity and Configuration")
    class IdentityAndConfig {

        @Test
        @DisplayName("Returns configured values")
        void returnsConfiguredValues() {
            assertThat(strategy.getId()).isEqualTo("STR-1");
            assertThat(strategy.getName()).isEqualTo("Test Straddle");
            assertThat(strategy.getUnderlying()).isEqualTo("NIFTY");
            assertThat(strategy.getType()).isEqualTo(StrategyType.STRADDLE);
        }

        @Test
        @DisplayName("Default stale data threshold is 5 seconds")
        void defaultStaleDataThreshold() {
            assertThat(strategy.getStaleDataThreshold()).isEqualTo(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("Default adjustment cooldown is 5 minutes")
        void defaultAdjustmentCooldown() {
            assertThat(strategy.getAdjustmentCooldown()).isEqualTo(Duration.ofMinutes(5));
        }
    }

    // ========================
    // CONCURRENCY
    // ========================

    @Nested
    @DisplayName("StampedLock Concurrency")
    class Concurrency {

        @Test
        @DisplayName("Concurrent position updates do not lose data")
        void concurrentPositionUpdatesPreserveData() throws Exception {
            int threadCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final String posId = "P-" + i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        strategy.addPosition(buildPosition(posId, "NIFTY24FEB22000CE", -50, BigDecimal.ZERO));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await();
            executor.shutdown();

            assertThat(strategy.getPositions()).hasSize(threadCount);
        }

        @Test
        @DisplayName("Lifecycle change during evaluation does not corrupt state")
        void lifecycleChangeDuringEvaluation() throws Exception {
            strategy.arm();
            strategy.setShouldEnter(true);
            strategy.evaluate(snapshot()); // -> ACTIVE

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch evalDone = new CountDownLatch(1);
            CountDownLatch pauseDone = new CountDownLatch(1);
            AtomicBoolean evalRan = new AtomicBoolean(false);

            // Thread 1: evaluate
            Thread evalThread = new Thread(() -> {
                try {
                    startLatch.await();
                    strategy.forceLastEvaluationToNull();
                    strategy.evaluate(snapshot());
                    evalRan.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    evalDone.countDown();
                }
            });

            // Thread 2: pause
            Thread pauseThread = new Thread(() -> {
                try {
                    startLatch.await();
                    strategy.pause();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    pauseDone.countDown();
                }
            });

            evalThread.start();
            pauseThread.start();
            startLatch.countDown();

            evalDone.await();
            pauseDone.await();

            // Status should be one of the valid states (ACTIVE or PAUSED)
            // depending on execution order - no corrupted intermediate state
            assertThat(strategy.getStatus()).isIn(StrategyStatus.ACTIVE, StrategyStatus.PAUSED, StrategyStatus.CLOSING);
        }
    }

    // ========================
    // DECISION LOGGING
    // ========================

    @Nested
    @DisplayName("Decision Logging")
    class DecisionLogging {

        @Test
        @DisplayName("Evaluation logs entry decision")
        void evaluationLogsEntryDecision() {
            strategy.arm();
            strategy.evaluate(snapshot());

            verify(eventPublisherHelper).publishDecision(any(), eq("ENTRY_EVAL"), anyString(), eq("STR-1"), any());
        }

        @Test
        @DisplayName("Strategy works without eventPublisherHelper (fallback to SLF4J)")
        void worksWithoutEventPublisher() {
            TestStrategy noPublisher = new TestStrategy("STR-NP", "No Publisher", config);
            // Do NOT call setServices -- eventPublisherHelper stays null

            noPublisher.arm();
            assertThat(noPublisher.getStatus()).isEqualTo(StrategyStatus.ARMED);
        }
    }

    // ========================
    // TEST STRATEGY IMPLEMENTATION
    // ========================

    /**
     * Concrete strategy for testing BaseStrategy behavior.
     * Exposes counters for tracking abstract method calls and setters
     * for controlling return values.
     */
    static class TestStrategy extends BaseStrategy {

        final AtomicInteger shouldEnterCallCount = new AtomicInteger(0);
        final AtomicInteger shouldExitCallCount = new AtomicInteger(0);
        final AtomicInteger adjustCallCount = new AtomicInteger(0);
        private volatile boolean shouldEnterResult = false;
        private volatile boolean shouldExitResult = false;
        private volatile List<OrderRequest> entryOrders = List.of(OrderRequest.builder()
                .tradingSymbol("NIFTY24FEB22000CE")
                .instrumentToken(256265L)
                .quantity(50)
                .build());

        TestStrategy(String id, String name, BaseStrategyConfig config) {
            super(id, name, config);
        }

        void setShouldEnter(boolean value) {
            this.shouldEnterResult = value;
        }

        void setShouldExit(boolean value) {
            this.shouldExitResult = value;
        }

        void setEntryOrders(List<OrderRequest> orders) {
            this.entryOrders = orders;
        }

        void forceLastEvaluationToNull() {
            this.lastEvaluationTime = null;
        }

        void forceLastEvaluationTime(LocalDateTime time) {
            this.lastEvaluationTime = time;
        }

        void forceLastAdjustmentTime(LocalDateTime time) {
            this.lastAdjustmentTime = time;
        }

        void forceEntryPremium(BigDecimal premium) {
            this.entryPremium = premium;
        }

        // Expose protected methods for testing
        BigDecimal callCalculatePositionDelta() {
            return calculatePositionDelta();
        }

        Position callGetShortCall() {
            return getShortCall();
        }

        Position callGetShortPut() {
            return getShortPut();
        }

        BigDecimal callRoundToStrike(BigDecimal price) {
            return roundToStrike(price);
        }

        void callRecordAdjustment(String type) {
            recordAdjustment(type);
        }

        boolean callIsTargetReached(BigDecimal targetPercent) {
            return isTargetReached(targetPercent);
        }

        boolean callIsStopLossHit(BigDecimal stopLossMultiplier) {
            return isStopLossHit(stopLossMultiplier);
        }

        @Override
        protected boolean shouldEnter(MarketSnapshot snapshot) {
            shouldEnterCallCount.incrementAndGet();
            return shouldEnterResult;
        }

        @Override
        protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
            return entryOrders;
        }

        @Override
        protected boolean shouldExit(MarketSnapshot snapshot) {
            shouldExitCallCount.incrementAndGet();
            return shouldExitResult;
        }

        @Override
        protected void adjust(MarketSnapshot snapshot) {
            adjustCallCount.incrementAndGet();
        }

        @Override
        public StrategyType getType() {
            return StrategyType.STRADDLE;
        }

        @Override
        public List<StrategyType> supportedMorphs() {
            return List.of(StrategyType.STRANGLE);
        }

        @Override
        public Duration getMonitoringInterval() {
            return Duration.ofMinutes(5);
        }
    }
}
