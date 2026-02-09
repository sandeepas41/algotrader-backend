package com.algotrader.unit.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.domain.enums.JournalStatus;
import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.entity.ExecutionJournalEntity;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.mapper.ExecutionJournalMapper;
import com.algotrader.oms.JournaledMultiLegExecutor;
import com.algotrader.oms.JournaledMultiLegExecutor.LegResult;
import com.algotrader.oms.JournaledMultiLegExecutor.MultiLegResult;
import com.algotrader.oms.OrderRequest;
import com.algotrader.oms.OrderRouteResult;
import com.algotrader.oms.OrderRouter;
import com.algotrader.oms.OrderTagGenerator;
import com.algotrader.repository.jpa.ExecutionJournalJpaRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for JournaledMultiLegExecutor covering WAL journaling,
 * sequential/parallel execution, rollback on partial failure, and edge cases.
 */
class JournaledMultiLegExecutorTest {

    private OrderRouter orderRouter;
    private OrderTagGenerator orderTagGenerator;
    private ExecutionJournalJpaRepository executionJournalJpaRepository;
    private ExecutionJournalMapper executionJournalMapper;
    private EventPublisherHelper eventPublisherHelper;
    private JournaledMultiLegExecutor journaledMultiLegExecutor;

    @BeforeEach
    void setUp() {
        orderRouter = mock(OrderRouter.class);
        orderTagGenerator = mock(OrderTagGenerator.class);
        executionJournalJpaRepository = mock(ExecutionJournalJpaRepository.class);
        executionJournalMapper = mock(ExecutionJournalMapper.class);
        eventPublisherHelper = mock(EventPublisherHelper.class);

        journaledMultiLegExecutor = new JournaledMultiLegExecutor(
                orderRouter,
                orderTagGenerator,
                executionJournalJpaRepository,
                executionJournalMapper,
                eventPublisherHelper);

        // Default: tag generator returns a tag
        when(orderTagGenerator.generate(anyString(), any(OrderPriority.class))).thenReturn("STRENT0001");

        // Default: save returns the entity as-is (simulating JPA save)
        when(executionJournalJpaRepository.save(any(ExecutionJournalEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** Tracks statuses at save-time since entities are mutable and reused. */
    private List<JournalStatus> captureJournalStatusesAtSaveTime() {
        List<JournalStatus> statuses = new ArrayList<>();
        when(executionJournalJpaRepository.save(any(ExecutionJournalEntity.class)))
                .thenAnswer(invocation -> {
                    ExecutionJournalEntity entity = invocation.getArgument(0);
                    statuses.add(entity.getStatus());
                    return entity;
                });
        return statuses;
    }

    private OrderRequest buildLeg(String symbol, OrderSide side) {
        return OrderRequest.builder()
                .instrumentToken(256265L)
                .tradingSymbol(symbol)
                .exchange("NFO")
                .side(side)
                .type(OrderType.LIMIT)
                .quantity(50)
                .price(BigDecimal.valueOf(100.0))
                .strategyId("STR1")
                .build();
    }

    private List<OrderRequest> ironCondorLegs() {
        return List.of(
                buildLeg("NIFTY24FEB22000CE", OrderSide.SELL),
                buildLeg("NIFTY24FEB22100CE", OrderSide.BUY),
                buildLeg("NIFTY24FEB21800PE", OrderSide.SELL),
                buildLeg("NIFTY24FEB21700PE", OrderSide.BUY));
    }

    @Nested
    @DisplayName("WAL Journaling")
    class WalJournaling {

        @Test
        @DisplayName("Creates PENDING journal entries for all legs before execution")
        void createsPendingJournalEntries() {
            // Capture statuses at save-time (entities are mutable, so we can't inspect after the fact)
            List<JournalStatus> statuses = captureJournalStatusesAtSaveTime();
            when(orderRouter.route(any(), any())).thenReturn(OrderRouteResult.accepted(null));

            journaledMultiLegExecutor.executeSequential(
                    ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            // 4 PENDING saves + 4 IN_PROGRESS saves + 4 COMPLETED saves = 12 saves
            assertThat(statuses).hasSize(12);
            // First 4 saves should be PENDING (WAL entries created before any execution)
            for (int i = 0; i < 4; i++) {
                assertThat(statuses.get(i)).isEqualTo(JournalStatus.PENDING);
            }
        }

        @Test
        @DisplayName("Journal entries have correct executionGroupId linking them")
        void journalEntriesShareGroupId() {
            when(orderRouter.route(any(), any())).thenReturn(OrderRouteResult.accepted(null));

            journaledMultiLegExecutor.executeSequential(
                    ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            ArgumentCaptor<ExecutionJournalEntity> captor = ArgumentCaptor.forClass(ExecutionJournalEntity.class);
            verify(executionJournalJpaRepository, times(12)).save(captor.capture());

            // All entries in the first group of 4 should share the same groupId
            String groupId = captor.getAllValues().get(0).getExecutionGroupId();
            assertThat(groupId).isNotNull().isNotEmpty();
            for (int i = 1; i < 4; i++) {
                assertThat(captor.getAllValues().get(i).getExecutionGroupId()).isEqualTo(groupId);
            }
        }

        @Test
        @DisplayName("Journal entries record strategy and operation type")
        void journalEntriesRecordContext() {
            when(orderRouter.route(any(), any())).thenReturn(OrderRouteResult.accepted(null));

            journaledMultiLegExecutor.executeSequential(
                    ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            ArgumentCaptor<ExecutionJournalEntity> captor = ArgumentCaptor.forClass(ExecutionJournalEntity.class);
            verify(executionJournalJpaRepository, times(12)).save(captor.capture());

            ExecutionJournalEntity first = captor.getAllValues().get(0);
            assertThat(first.getStrategyId()).isEqualTo("STR1");
            assertThat(first.getOperationType()).isEqualTo("ENTRY");
        }
    }

    @Nested
    @DisplayName("Sequential Execution")
    class SequentialExecution {

        @Test
        @DisplayName("All legs succeed returns success result")
        void allLegsSucceed() {
            when(orderRouter.route(any(), any())).thenReturn(OrderRouteResult.accepted(null));

            MultiLegResult result = journaledMultiLegExecutor.executeSequential(
                    ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getLegResults()).hasSize(4);
            assertThat(result.getLegResults()).allMatch(LegResult::isSuccess);
        }

        @Test
        @DisplayName("Result contains a valid groupId")
        void resultContainsGroupId() {
            when(orderRouter.route(any(), any())).thenReturn(OrderRouteResult.accepted(null));

            MultiLegResult result = journaledMultiLegExecutor.executeSequential(
                    ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            assertThat(result.getGroupId()).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("Stops on first failure and marks remaining legs as skipped")
        void stopsOnFirstFailure() {
            // Leg 0 succeeds, leg 1 fails, legs 2-3 should be skipped
            when(orderRouter.route(any(), any()))
                    .thenReturn(OrderRouteResult.accepted(null))
                    .thenReturn(OrderRouteResult.rejected("Insufficient margin"));

            MultiLegResult result = journaledMultiLegExecutor.executeSequential(
                    ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getLegResults().get(0).isSuccess()).isTrue();
            assertThat(result.getLegResults().get(1).isSuccess()).isFalse();
            assertThat(result.getLegResults().get(1).getFailureReason()).isEqualTo("Insufficient margin");
            // Legs 2 and 3 should be skipped
            assertThat(result.getLegResults().get(2).isSuccess()).isFalse();
            assertThat(result.getLegResults().get(2).getFailureReason()).contains("Skipped");
            assertThat(result.getLegResults().get(3).isSuccess()).isFalse();
            assertThat(result.getLegResults().get(3).getFailureReason()).contains("Skipped");
        }

        @Test
        @DisplayName("Routes each leg through OrderRouter")
        void routesEachLeg() {
            when(orderRouter.route(any(), any())).thenReturn(OrderRouteResult.accepted(null));

            journaledMultiLegExecutor.executeSequential(
                    ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            verify(orderRouter, times(4)).route(any(OrderRequest.class), eq(OrderPriority.STRATEGY_ENTRY));
        }

        @Test
        @DisplayName("Sets correlationId on each leg before routing")
        void setsCorrelationIdOnLegs() {
            when(orderRouter.route(any(), any())).thenReturn(OrderRouteResult.accepted(null));

            MultiLegResult result = journaledMultiLegExecutor.executeSequential(
                    ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
            verify(orderRouter, times(4)).route(captor.capture(), any());

            // All legs should have the groupId as correlationId
            for (OrderRequest routed : captor.getAllValues()) {
                assertThat(routed.getCorrelationId()).isEqualTo(result.getGroupId());
            }
        }
    }

    @Nested
    @DisplayName("Parallel Execution")
    class ParallelExecution {

        @Test
        @DisplayName("All legs succeed in parallel mode")
        void allLegsSucceedParallel() {
            when(orderRouter.route(any(), any())).thenReturn(OrderRouteResult.accepted(null));

            MultiLegResult result = journaledMultiLegExecutor.executeParallel(
                    ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getLegResults()).hasSize(4);
            assertThat(result.getLegResults()).allMatch(LegResult::isSuccess);
        }

        @Test
        @DisplayName("Parallel mode attempts all legs and returns results for each")
        void parallelModeAttemptsAllLegs() {
            // In parallel mode, all legs are submitted regardless of individual failures
            when(orderRouter.route(any(), any()))
                    .thenReturn(OrderRouteResult.accepted(null))
                    .thenReturn(OrderRouteResult.rejected("Failed"))
                    .thenReturn(OrderRouteResult.accepted(null))
                    .thenReturn(OrderRouteResult.accepted(null));

            MultiLegResult result = journaledMultiLegExecutor.executeParallel(
                    ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            assertThat(result.isSuccess()).isFalse();
            // All 4 legs should have results (not skipped like sequential mode)
            assertThat(result.getLegResults()).hasSize(4);
            // 4 original routes + 3 rollback routes = 7 total route calls
            verify(orderRouter, times(7)).route(any(OrderRequest.class), any());
        }

        @Test
        @DisplayName("Parallel failure triggers rollback of successful legs")
        void parallelFailureTriggersRollback() {
            // Leg 0,2,3 succeed, leg 1 fails -> rollback legs 0,2,3
            when(orderRouter.route(any(), any()))
                    .thenReturn(OrderRouteResult.accepted(null))
                    .thenReturn(OrderRouteResult.rejected("Market closed"))
                    .thenReturn(OrderRouteResult.accepted(null))
                    .thenReturn(OrderRouteResult.accepted(null));

            journaledMultiLegExecutor.executeParallel(ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            // 4 original routes + 3 rollback routes = 7 total
            verify(orderRouter, times(7)).route(any(OrderRequest.class), any());
        }
    }

    @Nested
    @DisplayName("Rollback")
    class Rollback {

        @Test
        @DisplayName("Rollback places opposite-side orders for filled legs")
        void rollbackPlacesOppositeSideOrders() {
            // Leg 0 (SELL) succeeds, leg 1 fails
            when(orderRouter.route(any(), any()))
                    .thenReturn(OrderRouteResult.accepted(null))
                    .thenReturn(OrderRouteResult.rejected("Failed"));

            List<OrderRequest> legs = List.of(
                    buildLeg("NIFTY24FEB22000CE", OrderSide.SELL), buildLeg("NIFTY24FEB21800PE", OrderSide.BUY));

            journaledMultiLegExecutor.executeSequential(legs, "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            // 2 original routes + 1 rollback route = 3 total
            ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
            verify(orderRouter, times(3)).route(captor.capture(), any());

            // The 3rd call should be the rollback for leg 0 (SELL -> BUY)
            OrderRequest rollbackOrder = captor.getAllValues().get(2);
            assertThat(rollbackOrder.getSide()).isEqualTo(OrderSide.BUY);
            assertThat(rollbackOrder.getTradingSymbol()).isEqualTo("NIFTY24FEB22000CE");
        }

        @Test
        @DisplayName("Rollback preserves quantity and price from original leg")
        void rollbackPreservesQuantityAndPrice() {
            when(orderRouter.route(any(), any()))
                    .thenReturn(OrderRouteResult.accepted(null))
                    .thenReturn(OrderRouteResult.rejected("Failed"));

            List<OrderRequest> legs = List.of(
                    buildLeg("NIFTY24FEB22000CE", OrderSide.SELL), buildLeg("NIFTY24FEB21800PE", OrderSide.BUY));

            journaledMultiLegExecutor.executeSequential(legs, "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
            verify(orderRouter, times(3)).route(captor.capture(), any());

            OrderRequest rollbackOrder = captor.getAllValues().get(2);
            assertThat(rollbackOrder.getQuantity()).isEqualTo(50);
            assertThat(rollbackOrder.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(100.0));
        }

        @Test
        @DisplayName("No rollback when all legs succeed")
        void noRollbackWhenAllSucceed() {
            when(orderRouter.route(any(), any())).thenReturn(OrderRouteResult.accepted(null));

            journaledMultiLegExecutor.executeSequential(
                    ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            // Only 4 calls (the original legs), no rollback calls
            verify(orderRouter, times(4)).route(any(OrderRequest.class), any());
        }

        @Test
        @DisplayName("No rollback when first leg fails (nothing to roll back)")
        void noRollbackWhenFirstLegFails() {
            when(orderRouter.route(any(), any())).thenReturn(OrderRouteResult.rejected("Rejected"));

            journaledMultiLegExecutor.executeSequential(
                    ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            // Only 1 call (the first leg that failed), legs 2-3 skipped, no rollback
            verify(orderRouter, times(1)).route(any(OrderRequest.class), any());
        }

        @Test
        @DisplayName("Rollback correlationId contains ROLLBACK prefix")
        void rollbackCorrelationIdContainsPrefix() {
            when(orderRouter.route(any(), any()))
                    .thenReturn(OrderRouteResult.accepted(null))
                    .thenReturn(OrderRouteResult.rejected("Failed"));

            List<OrderRequest> legs = List.of(
                    buildLeg("NIFTY24FEB22000CE", OrderSide.SELL), buildLeg("NIFTY24FEB21800PE", OrderSide.BUY));

            journaledMultiLegExecutor.executeSequential(legs, "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
            verify(orderRouter, times(3)).route(captor.capture(), any());

            OrderRequest rollbackOrder = captor.getAllValues().get(2);
            assertThat(rollbackOrder.getCorrelationId()).startsWith("ROLLBACK-");
        }
    }

    @Nested
    @DisplayName("Decision Logging")
    class DecisionLogging {

        @Test
        @DisplayName("Logs decision on successful multi-leg completion")
        void logsDecisionOnSuccess() {
            when(orderRouter.route(any(), any())).thenReturn(OrderRouteResult.accepted(null));

            journaledMultiLegExecutor.executeSequential(
                    ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            verify(eventPublisherHelper).publishDecision(any(), eq("ORDER"), anyString(), eq("STR1"), any(Map.class));
        }

        @Test
        @DisplayName("Logs decision on failed multi-leg operation")
        void logsDecisionOnFailure() {
            when(orderRouter.route(any(), any())).thenReturn(OrderRouteResult.rejected("Failed"));

            journaledMultiLegExecutor.executeSequential(
                    ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            verify(eventPublisherHelper).publishDecision(any(), eq("ORDER"), anyString(), eq("STR1"), any(Map.class));
        }
    }

    @Nested
    @DisplayName("Exception Handling")
    class ExceptionHandling {

        @Test
        @DisplayName("Exception in leg execution is caught and treated as failure")
        void exceptionTreatedAsFailure() {
            when(orderRouter.route(any(), any())).thenThrow(new RuntimeException("Connection lost"));

            MultiLegResult result = journaledMultiLegExecutor.executeSequential(
                    ironCondorLegs(), "STR1", "ENTRY", OrderPriority.STRATEGY_ENTRY);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getLegResults().get(0).isSuccess()).isFalse();
            assertThat(result.getLegResults().get(0).getFailureReason()).isEqualTo("Connection lost");
        }

        @Test
        @DisplayName("Exception updates journal to FAILED status")
        void exceptionUpdatesJournalToFailed() {
            when(orderRouter.route(any(), any())).thenThrow(new RuntimeException("Timeout"));

            journaledMultiLegExecutor.executeSequential(
                    List.of(buildLeg("NIFTY24FEB22000CE", OrderSide.SELL)),
                    "STR1",
                    "ENTRY",
                    OrderPriority.STRATEGY_ENTRY);

            // 1 PENDING + 1 IN_PROGRESS + 1 FAILED = 3 saves
            ArgumentCaptor<ExecutionJournalEntity> captor = ArgumentCaptor.forClass(ExecutionJournalEntity.class);
            verify(executionJournalJpaRepository, times(3)).save(captor.capture());

            ExecutionJournalEntity lastSave = captor.getAllValues().get(2);
            assertThat(lastSave.getStatus()).isEqualTo(JournalStatus.FAILED);
            assertThat(lastSave.getFailureReason()).isEqualTo("Timeout");
        }
    }

    @Nested
    @DisplayName("Single Leg")
    class SingleLeg {

        @Test
        @DisplayName("Single leg success works correctly")
        void singleLegSuccess() {
            when(orderRouter.route(any(), any())).thenReturn(OrderRouteResult.accepted(null));

            MultiLegResult result = journaledMultiLegExecutor.executeSequential(
                    List.of(buildLeg("NIFTY24FEB22000CE", OrderSide.SELL)),
                    "STR1",
                    "EXIT",
                    OrderPriority.STRATEGY_EXIT);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getLegResults()).hasSize(1);
            assertThat(result.getLegResults().get(0).getLegIndex()).isEqualTo(0);
        }

        @Test
        @DisplayName("Single leg failure returns failure result without rollback")
        void singleLegFailure() {
            when(orderRouter.route(any(), any())).thenReturn(OrderRouteResult.rejected("No margin"));

            MultiLegResult result = journaledMultiLegExecutor.executeSequential(
                    List.of(buildLeg("NIFTY24FEB22000CE", OrderSide.SELL)),
                    "STR1",
                    "EXIT",
                    OrderPriority.STRATEGY_EXIT);

            assertThat(result.isSuccess()).isFalse();
            // Only 1 route call (the failed leg), no rollback needed
            verify(orderRouter, times(1)).route(any(), any());
        }
    }

    @Nested
    @DisplayName("LegResult Static Factories")
    class LegResultFactories {

        @Test
        @DisplayName("LegResult.success creates successful result with tag")
        void successFactory() {
            LegResult result = LegResult.success(2, "STRENT0003");

            assertThat(result.getLegIndex()).isEqualTo(2);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getTag()).isEqualTo("STRENT0003");
            assertThat(result.getFailureReason()).isNull();
        }

        @Test
        @DisplayName("LegResult.failed creates failed result with reason")
        void failedFactory() {
            LegResult result = LegResult.failed(1, "Margin exceeded");

            assertThat(result.getLegIndex()).isEqualTo(1);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).isEqualTo("Margin exceeded");
            assertThat(result.getTag()).isNull();
        }

        @Test
        @DisplayName("LegResult.skipped creates skipped result")
        void skippedFactory() {
            LegResult result = LegResult.skipped(3);

            assertThat(result.getLegIndex()).isEqualTo(3);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureReason()).contains("Skipped");
        }
    }
}
