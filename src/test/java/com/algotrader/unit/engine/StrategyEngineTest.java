package com.algotrader.unit.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.ActionType;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.AdjustmentAction;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.Tick;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.event.PositionEvent;
import com.algotrader.event.PositionEventType;
import com.algotrader.event.TickEvent;
import com.algotrader.exception.ResourceNotFoundException;
import com.algotrader.oms.JournaledMultiLegExecutor;
import com.algotrader.oms.OrderRequest;
import com.algotrader.service.InstrumentService;
import com.algotrader.strategy.StrategyFactory;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.BaseStrategyConfig;
import com.algotrader.strategy.base.MarketSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for StrategyEngine covering deployment, lifecycle management,
 * tick routing, position updates, force adjustments, pauseAll, and concurrency.
 */
class StrategyEngineTest {

    private StrategyFactory strategyFactory;
    private EventPublisherHelper eventPublisherHelper;
    private JournaledMultiLegExecutor journaledMultiLegExecutor;
    private InstrumentService instrumentService;
    private StrategyEngine strategyEngine;

    private BaseStrategyConfig defaultConfig;

    @BeforeEach
    void setUp() {
        strategyFactory = mock(StrategyFactory.class);
        eventPublisherHelper = mock(EventPublisherHelper.class);
        journaledMultiLegExecutor = mock(JournaledMultiLegExecutor.class);
        instrumentService = mock(InstrumentService.class);

        strategyEngine =
                new StrategyEngine(strategyFactory, eventPublisherHelper, journaledMultiLegExecutor, instrumentService);

        defaultConfig = BaseStrategyConfig.builder()
                .underlying("NIFTY")
                .lots(1)
                .strikeInterval(BigDecimal.valueOf(50))
                .entryStartTime(LocalTime.of(9, 15))
                .entryEndTime(LocalTime.of(15, 15))
                .build();
    }

    /** Creates a TestStrategy and stubs the factory to return it. */
    private TestStrategy stubFactory(String id) {
        TestStrategy strategy = new TestStrategy(id, "Test Strategy", defaultConfig);
        when(strategyFactory.create(any(StrategyType.class), anyString(), any(BaseStrategyConfig.class)))
                .thenReturn(strategy);
        return strategy;
    }

    /** Deploys a strategy through the engine, returning the strategy ID. */
    private String deployTestStrategy(String id) {
        stubFactory(id);
        return strategyEngine.deployStrategy(StrategyType.STRADDLE, "Test Strategy", defaultConfig, false);
    }

    // ========================
    // DEPLOYMENT
    // ========================

    @Nested
    @DisplayName("Deployment")
    class Deployment {

        @Test
        @DisplayName("Deploy creates strategy and registers it")
        void deployCreatesAndRegisters() {
            String id = deployTestStrategy("STR-001");

            assertThat(id).isEqualTo("STR-001");
            assertThat(strategyEngine.getActiveStrategyCount()).isEqualTo(1);
            assertThat(strategyEngine.getStrategy("STR-001")).isNotNull();
        }

        @Test
        @DisplayName("Deploy with autoArm transitions strategy to ARMED")
        void deployWithAutoArm() {
            stubFactory("STR-002");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "Auto-Arm Test", defaultConfig, true);

            assertThat(strategyEngine.getStrategy("STR-002").getStatus()).isEqualTo(StrategyStatus.ARMED);
        }

        @Test
        @DisplayName("Deploy publishes decision event")
        void deployPublishesDecision() {
            deployTestStrategy("STR-003");

            verify(eventPublisherHelper)
                    .publishDecision(any(), eq("DEPLOY"), eq("Strategy deployed"), eq("STR-003"), any(Map.class));
        }

        @Test
        @DisplayName("Newly deployed strategy starts in CREATED state")
        void deployedStrategyStartsCreated() {
            deployTestStrategy("STR-004");

            assertThat(strategyEngine.getStrategy("STR-004").getStatus()).isEqualTo(StrategyStatus.CREATED);
        }
    }

    // ========================
    // LIFECYCLE
    // ========================

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("Full lifecycle: deploy -> arm -> active -> pause -> resume -> close")
        void fullLifecycle() {
            TestStrategy strategy = stubFactory("STR-LC");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "Lifecycle Test", defaultConfig, false);

            // arm
            strategyEngine.armStrategy("STR-LC");
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.ARMED);

            // simulate entry -> ACTIVE (directly set for testing)
            strategy.forceStatus(StrategyStatus.ACTIVE);

            // pause
            strategyEngine.pauseStrategy("STR-LC");
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.PAUSED);

            // resume
            strategyEngine.resumeStrategy("STR-LC");
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.ACTIVE);

            // close
            strategyEngine.closeStrategy("STR-LC");
            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.CLOSING);
        }

        @Test
        @DisplayName("Arm on non-existent strategy throws ResourceNotFoundException")
        void armNonExistentThrows() {
            assertThatThrownBy(() -> strategyEngine.armStrategy("DOES-NOT-EXIST"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Lifecycle transitions publish decision events")
        void lifecyclePublishesDecisions() {
            TestStrategy strategy = stubFactory("STR-EVT");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "Events Test", defaultConfig, false);

            strategyEngine.armStrategy("STR-EVT");
            strategy.forceStatus(StrategyStatus.ACTIVE);
            strategyEngine.pauseStrategy("STR-EVT");
            strategyEngine.resumeStrategy("STR-EVT");

            // 1 deploy + 1 arm + 1 pause + 1 resume = 4 decisions from engine (plus strategy's own)
            verify(eventPublisherHelper, times(4))
                    .publishDecision(eq(strategyEngine), anyString(), anyString(), eq("STR-EVT"), any(Map.class));
        }
    }

    // ========================
    // UNDEPLOY
    // ========================

    @Nested
    @DisplayName("Undeploy")
    class Undeploy {

        @Test
        @DisplayName("Undeploy removes closed strategy from registry")
        void undeployRemovesClosedStrategy() {
            TestStrategy strategy = stubFactory("STR-UD");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "Undeploy Test", defaultConfig, false);
            strategy.forceStatus(StrategyStatus.CLOSED);

            strategyEngine.undeployStrategy("STR-UD");

            assertThat(strategyEngine.getActiveStrategyCount()).isZero();
        }

        @Test
        @DisplayName("Undeploy rejects non-CLOSED strategy")
        void undeployRejectsNonClosed() {
            TestStrategy strategy = stubFactory("STR-UD2");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "Undeploy Test 2", defaultConfig, false);
            strategy.forceStatus(StrategyStatus.ACTIVE);

            assertThatThrownBy(() -> strategyEngine.undeployStrategy("STR-UD2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot undeploy");
        }
    }

    // ========================
    // PAUSE ALL
    // ========================

    @Nested
    @DisplayName("Pause All")
    class PauseAll {

        @Test
        @DisplayName("Pauses all ARMED and ACTIVE strategies")
        void pausesAllArmedAndActive() {
            TestStrategy s1 = stubFactory("STR-PA1");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "S1", defaultConfig, false);
            s1.forceStatus(StrategyStatus.ARMED);

            TestStrategy s2 = new TestStrategy("STR-PA2", "S2", defaultConfig);
            when(strategyFactory.create(any(), anyString(), any())).thenReturn(s2);
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "S2", defaultConfig, false);
            s2.forceStatus(StrategyStatus.ACTIVE);

            TestStrategy s3 = new TestStrategy("STR-PA3", "S3", defaultConfig);
            when(strategyFactory.create(any(), anyString(), any())).thenReturn(s3);
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "S3", defaultConfig, false);
            s3.forceStatus(StrategyStatus.CLOSED);

            strategyEngine.pauseAll();

            assertThat(s1.getStatus()).isEqualTo(StrategyStatus.PAUSED);
            assertThat(s2.getStatus()).isEqualTo(StrategyStatus.PAUSED);
            // s3 was already CLOSED, should not be paused
            assertThat(s3.getStatus()).isEqualTo(StrategyStatus.CLOSED);
        }
    }

    // ========================
    // FORCE ADJUSTMENT
    // ========================

    @Nested
    @DisplayName("Force Adjustment")
    class ForceAdjustment {

        @Test
        @DisplayName("Force CLOSE_ALL initiates close on strategy")
        void forceCloseAllInitiatesClose() {
            TestStrategy strategy = stubFactory("STR-FA");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "FA Test", defaultConfig, false);
            strategy.forceStatus(StrategyStatus.ACTIVE);

            AdjustmentAction action = AdjustmentAction.builder()
                    .type(ActionType.CLOSE_ALL)
                    .parameters(Map.of())
                    .build();

            strategyEngine.forceAdjustment("STR-FA", action);

            assertThat(strategy.getStatus()).isEqualTo(StrategyStatus.CLOSING);
        }

        @Test
        @DisplayName("Force adjustment on non-ACTIVE strategy throws")
        void forceAdjustOnNonActiveThrows() {
            TestStrategy strategy = stubFactory("STR-FA2");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "FA Test 2", defaultConfig, false);
            strategy.forceStatus(StrategyStatus.PAUSED);

            AdjustmentAction action = AdjustmentAction.builder()
                    .type(ActionType.ROLL_UP)
                    .parameters(Map.of())
                    .build();

            assertThatThrownBy(() -> strategyEngine.forceAdjustment("STR-FA2", action))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ACTIVE");
        }

        @Test
        @DisplayName("Force adjustment publishes decision event")
        void forceAdjustmentPublishesDecision() {
            TestStrategy strategy = stubFactory("STR-FA3");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "FA Test 3", defaultConfig, false);
            strategy.forceStatus(StrategyStatus.ACTIVE);

            AdjustmentAction action = AdjustmentAction.builder()
                    .type(ActionType.ADD_HEDGE)
                    .parameters(Map.of("offset", 3))
                    .build();

            strategyEngine.forceAdjustment("STR-FA3", action);

            verify(eventPublisherHelper)
                    .publishDecision(
                            eq(strategyEngine), eq("MANUAL_ADJUST"), anyString(), eq("STR-FA3"), any(Map.class));
        }
    }

    // ========================
    // TICK ROUTING
    // ========================

    @Nested
    @DisplayName("Tick Routing")
    class TickRouting {

        @Test
        @DisplayName("Tick routes to ARMED strategy")
        void tickRoutesToArmedStrategy() {
            TestStrategy strategy = stubFactory("STR-TR1");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "Tick Test", defaultConfig, false);
            strategyEngine.armStrategy("STR-TR1");

            TickEvent tickEvent = new TickEvent(
                    this,
                    Tick.builder()
                            .instrumentToken(256265L)
                            .lastPrice(BigDecimal.valueOf(22000))
                            .timestamp(LocalDateTime.now())
                            .build());

            strategyEngine.onTick(tickEvent);

            assertThat(strategy.evaluateCallCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Tick routes to ACTIVE strategy")
        void tickRoutesToActiveStrategy() {
            TestStrategy strategy = stubFactory("STR-TR2");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "Tick Test 2", defaultConfig, false);
            strategy.forceStatus(StrategyStatus.ACTIVE);

            TickEvent tickEvent = new TickEvent(
                    this,
                    Tick.builder()
                            .instrumentToken(256265L)
                            .lastPrice(BigDecimal.valueOf(22000))
                            .timestamp(LocalDateTime.now())
                            .build());

            strategyEngine.onTick(tickEvent);

            assertThat(strategy.evaluateCallCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Tick does NOT route to PAUSED strategy")
        void tickDoesNotRouteToPaused() {
            TestStrategy strategy = stubFactory("STR-TR3");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "Tick Test 3", defaultConfig, false);
            strategy.forceStatus(StrategyStatus.PAUSED);

            TickEvent tickEvent = new TickEvent(
                    this,
                    Tick.builder()
                            .instrumentToken(256265L)
                            .lastPrice(BigDecimal.valueOf(22000))
                            .timestamp(LocalDateTime.now())
                            .build());

            strategyEngine.onTick(tickEvent);

            assertThat(strategy.evaluateCallCount.get()).isZero();
        }

        @Test
        @DisplayName("Tick does NOT route to CLOSED strategy")
        void tickDoesNotRouteToClosed() {
            TestStrategy strategy = stubFactory("STR-TR4");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "Tick Test 4", defaultConfig, false);
            strategy.forceStatus(StrategyStatus.CLOSED);

            TickEvent tickEvent = new TickEvent(
                    this,
                    Tick.builder()
                            .instrumentToken(256265L)
                            .lastPrice(BigDecimal.valueOf(22000))
                            .timestamp(LocalDateTime.now())
                            .build());

            strategyEngine.onTick(tickEvent);

            assertThat(strategy.evaluateCallCount.get()).isZero();
        }

        @Test
        @DisplayName("Tick builds MarketSnapshot with correct spot price")
        void tickBuildsSnapshotWithSpotPrice() {
            TestStrategy strategy = stubFactory("STR-TR5");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "Tick Test 5", defaultConfig, false);
            strategyEngine.armStrategy("STR-TR5");

            TickEvent tickEvent = new TickEvent(
                    this,
                    Tick.builder()
                            .instrumentToken(256265L)
                            .lastPrice(BigDecimal.valueOf(22150))
                            .timestamp(LocalDateTime.now())
                            .build());

            strategyEngine.onTick(tickEvent);

            assertThat(strategy.lastSnapshot).isNotNull();
            assertThat(strategy.lastSnapshot.getSpotPrice()).isEqualByComparingTo(BigDecimal.valueOf(22150));
        }

        @Test
        @DisplayName("Exception in strategy evaluation does not crash tick routing")
        void exceptionInStrategyDoesNotCrash() {
            TestStrategy goodStrategy = new TestStrategy("STR-GOOD", "Good", defaultConfig);
            TestStrategy badStrategy = new TestStrategy("STR-BAD", "Bad", defaultConfig) {
                @Override
                public void evaluate(MarketSnapshot snapshot) {
                    evaluateCallCount.incrementAndGet();
                    throw new RuntimeException("Boom!");
                }
            };

            // Deploy bad strategy first
            when(strategyFactory.create(any(), anyString(), any())).thenReturn(badStrategy);
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "Bad", defaultConfig, true);

            // Deploy good strategy second
            when(strategyFactory.create(any(), anyString(), any())).thenReturn(goodStrategy);
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "Good", defaultConfig, true);

            TickEvent tickEvent = new TickEvent(
                    this,
                    Tick.builder()
                            .instrumentToken(256265L)
                            .lastPrice(BigDecimal.valueOf(22000))
                            .timestamp(LocalDateTime.now())
                            .build());

            // Should not throw -- exception is caught and logged
            strategyEngine.onTick(tickEvent);

            // Both should have been attempted
            assertThat(badStrategy.evaluateCallCount.get()).isEqualTo(1);
            assertThat(goodStrategy.evaluateCallCount.get()).isEqualTo(1);
        }
    }

    // ========================
    // POSITION UPDATES
    // ========================

    @Nested
    @DisplayName("Position Updates")
    class PositionUpdates {

        @Test
        @DisplayName("Position update routes to strategy via reverse index")
        void positionUpdateRoutesViaReverseIndex() {
            TestStrategy strategy = stubFactory("STR-PU1");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "PU Test", defaultConfig, false);
            strategy.forceStatus(StrategyStatus.ACTIVE);

            // Add a position to the strategy first
            strategy.addPosition(Position.builder()
                    .id("POS-1")
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .instrumentToken(256265L)
                    .quantity(-50)
                    .unrealizedPnl(BigDecimal.ZERO)
                    .lastUpdated(LocalDateTime.now())
                    .build());

            // Register the link in the reverse index
            strategyEngine.registerPositionLink("POS-1", "STR-PU1");

            // Send position update
            Position updated = Position.builder()
                    .id("POS-1")
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .instrumentToken(256265L)
                    .quantity(-50)
                    .unrealizedPnl(BigDecimal.valueOf(500))
                    .lastUpdated(LocalDateTime.now())
                    .build();

            PositionEvent positionEvent = new PositionEvent(this, updated, PositionEventType.UPDATED);
            strategyEngine.onPositionUpdate(positionEvent);

            // Verify position was updated in the strategy
            assertThat(strategy.getPositions()).hasSize(1);
            assertThat(strategy.getPositions().get(0).getUnrealizedPnl()).isEqualByComparingTo(BigDecimal.valueOf(500));
        }

        @Test
        @DisplayName("Position update for position not in reverse index is silently ignored")
        void positionUpdateForUnindexedPositionIgnored() {
            Position position = Position.builder()
                    .id("POS-UNKNOWN")
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .instrumentToken(256265L)
                    .quantity(-50)
                    .build();

            // Should not throw -- position not in reverse index
            strategyEngine.onPositionUpdate(new PositionEvent(this, position, PositionEventType.UPDATED));
        }

        @Test
        @DisplayName("Position update routes to multiple strategies via reverse index")
        void positionUpdateRoutesToMultipleStrategies() {
            TestStrategy s1 = stubFactory("STR-M1");
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "S1", defaultConfig, false);
            s1.forceStatus(StrategyStatus.ACTIVE);

            TestStrategy s2 = new TestStrategy("STR-M2", "S2", defaultConfig);
            when(strategyFactory.create(any(), anyString(), any())).thenReturn(s2);
            strategyEngine.deployStrategy(StrategyType.STRADDLE, "S2", defaultConfig, false);
            s2.forceStatus(StrategyStatus.ACTIVE);

            // Both strategies hold the same position
            Position pos = Position.builder()
                    .id("POS-SHARED")
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .instrumentToken(256265L)
                    .quantity(-50)
                    .unrealizedPnl(BigDecimal.ZERO)
                    .build();
            s1.addPosition(pos);
            s2.addPosition(pos);

            // Register both links
            strategyEngine.registerPositionLink("POS-SHARED", "STR-M1");
            strategyEngine.registerPositionLink("POS-SHARED", "STR-M2");

            // Send update
            Position updated = Position.builder()
                    .id("POS-SHARED")
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .instrumentToken(256265L)
                    .quantity(-50)
                    .unrealizedPnl(BigDecimal.valueOf(1000))
                    .build();

            strategyEngine.onPositionUpdate(new PositionEvent(this, updated, PositionEventType.UPDATED));

            // Both strategies should have the updated PnL
            assertThat(s1.getPositions().get(0).getUnrealizedPnl()).isEqualByComparingTo(BigDecimal.valueOf(1000));
            assertThat(s2.getPositions().get(0).getUnrealizedPnl()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        }
    }

    // ========================
    // REVERSE INDEX
    // ========================

    @Nested
    @DisplayName("Reverse Index")
    class ReverseIndex {

        @Test
        @DisplayName("registerPositionLink adds mapping")
        void registerAddsMapping() {
            strategyEngine.registerPositionLink("POS-1", "STR-A");

            assertThat(strategyEngine.getStrategiesForPosition("POS-1")).containsExactly("STR-A");
        }

        @Test
        @DisplayName("registerPositionLink supports multiple strategies for same position")
        void registerMultipleStrategies() {
            strategyEngine.registerPositionLink("POS-1", "STR-A");
            strategyEngine.registerPositionLink("POS-1", "STR-B");

            assertThat(strategyEngine.getStrategiesForPosition("POS-1")).containsExactlyInAnyOrder("STR-A", "STR-B");
        }

        @Test
        @DisplayName("unregisterPositionLink removes mapping")
        void unregisterRemovesMapping() {
            strategyEngine.registerPositionLink("POS-1", "STR-A");
            strategyEngine.unregisterPositionLink("POS-1", "STR-A");

            assertThat(strategyEngine.getStrategiesForPosition("POS-1")).isEmpty();
        }

        @Test
        @DisplayName("unregisterPositionLink removes only the specified strategy")
        void unregisterRemovesOnlySpecified() {
            strategyEngine.registerPositionLink("POS-1", "STR-A");
            strategyEngine.registerPositionLink("POS-1", "STR-B");

            strategyEngine.unregisterPositionLink("POS-1", "STR-A");

            assertThat(strategyEngine.getStrategiesForPosition("POS-1")).containsExactly("STR-B");
        }

        @Test
        @DisplayName("unregisterPositionLink is safe on missing position")
        void unregisterSafeOnMissing() {
            // Should not throw
            strategyEngine.unregisterPositionLink("POS-NONE", "STR-X");
            assertThat(strategyEngine.getStrategiesForPosition("POS-NONE")).isEmpty();
        }

        @Test
        @DisplayName("populatePositionIndex builds index from links")
        void populateBuildsIndex() {
            List<Map.Entry<String, String>> links =
                    List.of(Map.entry("POS-1", "STR-A"), Map.entry("POS-1", "STR-B"), Map.entry("POS-2", "STR-A"));

            strategyEngine.populatePositionIndex(links);

            assertThat(strategyEngine.getStrategiesForPosition("POS-1")).containsExactlyInAnyOrder("STR-A", "STR-B");
            assertThat(strategyEngine.getStrategiesForPosition("POS-2")).containsExactly("STR-A");
        }

        @Test
        @DisplayName("populatePositionIndex clears previous index")
        void populateClearsPrevious() {
            strategyEngine.registerPositionLink("POS-OLD", "STR-OLD");

            strategyEngine.populatePositionIndex(List.of(Map.entry("POS-NEW", "STR-NEW")));

            assertThat(strategyEngine.getStrategiesForPosition("POS-OLD")).isEmpty();
            assertThat(strategyEngine.getStrategiesForPosition("POS-NEW")).containsExactly("STR-NEW");
        }

        @Test
        @DisplayName("getStrategiesForPosition returns empty set for unknown position")
        void getStrategiesForUnknownPosition() {
            assertThat(strategyEngine.getStrategiesForPosition("POS-NOPE")).isEmpty();
        }
    }

    // ========================
    // QUERIES
    // ========================

    @Nested
    @DisplayName("Queries")
    class Queries {

        @Test
        @DisplayName("getActiveStrategies returns unmodifiable view")
        void getActiveStrategiesReturnsUnmodifiable() {
            deployTestStrategy("STR-Q1");

            Map<String, BaseStrategy> strategies = strategyEngine.getActiveStrategies();
            assertThat(strategies).hasSize(1);

            assertThatThrownBy(() -> strategies.put("HACK", null)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("getStrategiesByType filters correctly")
        void getStrategiesByTypeFilters() {
            deployTestStrategy("STR-Q2");

            List<BaseStrategy> straddles = strategyEngine.getStrategiesByType(StrategyType.STRADDLE);
            assertThat(straddles).hasSize(1);

            List<BaseStrategy> condors = strategyEngine.getStrategiesByType(StrategyType.IRON_CONDOR);
            assertThat(condors).isEmpty();
        }

        @Test
        @DisplayName("getLastEvaluationTime returns null for unevaluated strategy")
        void getLastEvaluationTimeReturnsNull() {
            deployTestStrategy("STR-Q3");

            assertThat(strategyEngine.getLastEvaluationTime("STR-Q3")).isNull();
        }

        @Test
        @DisplayName("getStrategy throws for non-existent ID")
        void getStrategyThrowsForNonExistent() {
            assertThatThrownBy(() -> strategyEngine.getStrategy("NOPE")).isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ========================
    // CONCURRENCY
    // ========================

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        @DisplayName("Concurrent deployments register all strategies")
        void concurrentDeploymentsRegisterAll() throws Exception {
            int threadCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            // Pre-create strategies in a thread-safe map, stub factory once with thenAnswer
            ConcurrentHashMap<String, TestStrategy> prebuilt = new ConcurrentHashMap<>();
            for (int i = 0; i < threadCount; i++) {
                prebuilt.put("Concurrent " + i, new TestStrategy("STR-CC-" + i, "Concurrent " + i, defaultConfig));
            }
            when(strategyFactory.create(any(), anyString(), any()))
                    .thenAnswer(inv -> prebuilt.get(inv.<String>getArgument(1)));

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        strategyEngine.deployStrategy(
                                StrategyType.STRADDLE, "Concurrent " + index, defaultConfig, false);
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

            assertThat(strategyEngine.getActiveStrategyCount()).isEqualTo(threadCount);
        }
    }

    // ========================
    // TEST STRATEGY
    // ========================

    /**
     * Concrete strategy for testing StrategyEngine behavior.
     * Tracks evaluate() calls and captures the last snapshot.
     */
    static class TestStrategy extends BaseStrategy {

        final AtomicInteger evaluateCallCount = new AtomicInteger(0);
        volatile MarketSnapshot lastSnapshot;

        TestStrategy(String id, String name, BaseStrategyConfig config) {
            super(id, name, config);
        }

        void forceStatus(StrategyStatus newStatus) {
            this.status = newStatus;
        }

        @Override
        public void evaluate(MarketSnapshot snapshot) {
            evaluateCallCount.incrementAndGet();
            lastSnapshot = snapshot;
            // Don't call super.evaluate() to avoid interval/stale data logic in engine tests
        }

        @Override
        protected boolean shouldEnter(MarketSnapshot snapshot) {
            return false;
        }

        @Override
        protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
            return List.of();
        }

        @Override
        protected boolean shouldExit(MarketSnapshot snapshot) {
            return false;
        }

        @Override
        protected void adjust(MarketSnapshot snapshot) {}

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
