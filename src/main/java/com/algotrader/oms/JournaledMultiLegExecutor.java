package com.algotrader.oms;

import com.algotrader.domain.enums.JournalStatus;
import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.entity.ExecutionJournalEntity;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.mapper.ExecutionJournalMapper;
import com.algotrader.repository.jpa.ExecutionJournalJpaRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes multi-leg order operations with write-ahead logging (WAL) for crash safety.
 *
 * <p>Every multi-leg operation (entry, adjustment, exit) follows this protocol:
 * <ol>
 *   <li>Create PENDING journal entries for ALL legs before executing any</li>
 *   <li>Execute legs (sequentially or in parallel depending on mode)</li>
 *   <li>Update journal entries as each leg succeeds or fails</li>
 *   <li>On partial failure, roll back filled legs by placing opposite orders</li>
 * </ol>
 *
 * <p>Two execution modes:
 * <ul>
 *   <li><b>Parallel mode</b> (for entry orders with independent legs): submit all legs
 *       concurrently via CompletableFuture. Reduces 4-leg Iron Condor latency from
 *       ~200ms (sequential) to ~50ms (parallel). Used when leg ordering doesn't matter.</li>
 *   <li><b>Sequential mode</b> (for adjustments where leg ordering matters): execute legs
 *       one by one, stopping on first failure. Used when a later leg depends on an earlier
 *       one (e.g., close existing position before opening new one).</li>
 * </ul>
 *
 * <p>If the application crashes mid-execution, the StartupRecoveryService (#TODO Phase 8)
 * reads incomplete journal entries (status IN_PROGRESS or REQUIRES_RECOVERY) and resolves
 * them on next startup.
 */
@Component
public class JournaledMultiLegExecutor {

    private static final Logger log = LoggerFactory.getLogger(JournaledMultiLegExecutor.class);

    private final OrderRouter orderRouter;
    private final OrderTagGenerator orderTagGenerator;
    private final ExecutionJournalJpaRepository executionJournalJpaRepository;
    private final ExecutionJournalMapper executionJournalMapper;
    private final EventPublisherHelper eventPublisherHelper;
    private final OrderFillTracker orderFillTracker;

    /**
     * Virtual thread executor for parallel leg submission.
     * Virtual threads are ideal here: each leg does blocking I/O (broker API call)
     * and we need high concurrency with low overhead.
     */
    private final ExecutorService parallelExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public JournaledMultiLegExecutor(
            OrderRouter orderRouter,
            OrderTagGenerator orderTagGenerator,
            ExecutionJournalJpaRepository executionJournalJpaRepository,
            ExecutionJournalMapper executionJournalMapper,
            EventPublisherHelper eventPublisherHelper,
            OrderFillTracker orderFillTracker) {
        this.orderRouter = orderRouter;
        this.orderTagGenerator = orderTagGenerator;
        this.executionJournalJpaRepository = executionJournalJpaRepository;
        this.executionJournalMapper = executionJournalMapper;
        this.eventPublisherHelper = eventPublisherHelper;
        this.orderFillTracker = orderFillTracker;
    }

    /**
     * Executes multiple order legs sequentially with WAL journaling.
     *
     * <p>Legs are executed one at a time, stopping on the first failure.
     * If a leg fails, all previously filled legs are rolled back (opposite orders).
     *
     * @param legs          the order requests for each leg
     * @param strategyId    the strategy this operation belongs to
     * @param operationType description of the operation (ENTRY, ADJUSTMENT, EXIT)
     * @param priority      the priority for all legs
     * @return result containing success/failure status and details per leg
     */
    public MultiLegResult executeSequential(
            List<OrderRequest> legs, String strategyId, String operationType, OrderPriority priority) {
        String groupId = UUID.randomUUID().toString();

        log.info(
                "Starting sequential multi-leg: groupId={}, strategy={}, operation={}, legs={}",
                groupId,
                strategyId,
                operationType,
                legs.size());

        // Step 1: Create PENDING journal entries for all legs
        List<ExecutionJournalEntity> journals = createJournalEntries(legs, strategyId, groupId, operationType);

        // Step 2: Execute legs sequentially
        List<LegResult> legResults = new ArrayList<>();
        boolean failed = false;

        for (int i = 0; i < legs.size(); i++) {
            if (failed) {
                // Mark remaining legs as skipped
                updateJournalStatus(journals.get(i), JournalStatus.FAILED, "Skipped due to prior leg failure");
                legResults.add(LegResult.skipped(i));
                continue;
            }

            LegResult legResult = executeLeg(legs.get(i), journals.get(i), strategyId, priority, i);
            legResults.add(legResult);

            if (!legResult.isSuccess()) {
                failed = true;
            }
        }

        // Step 3: If partial failure, roll back filled legs
        if (failed) {
            rollbackFilledLegs(legs, legResults, strategyId, priority);
        }

        MultiLegResult result = MultiLegResult.builder()
                .groupId(groupId)
                .success(!failed)
                .legResults(legResults)
                .build();

        logDecision(strategyId, operationType, result);
        return result;
    }

    /**
     * Executes multiple order legs in parallel with WAL journaling.
     *
     * <p>All legs are submitted concurrently. If any leg fails, all successfully
     * filled legs are rolled back. This mode is for entry orders where legs are
     * independent (e.g., Iron Condor 4-leg entry).
     *
     * @param legs          the order requests for each leg
     * @param strategyId    the strategy this operation belongs to
     * @param operationType description of the operation (typically "ENTRY")
     * @param priority      the priority for all legs
     * @return result containing success/failure status and details per leg
     */
    public MultiLegResult executeParallel(
            List<OrderRequest> legs, String strategyId, String operationType, OrderPriority priority) {
        String groupId = UUID.randomUUID().toString();

        log.info(
                "Starting parallel multi-leg: groupId={}, strategy={}, operation={}, legs={}",
                groupId,
                strategyId,
                operationType,
                legs.size());

        // Step 1: Create PENDING journal entries
        List<ExecutionJournalEntity> journals = createJournalEntries(legs, strategyId, groupId, operationType);

        // Step 2: Submit all legs in parallel
        List<CompletableFuture<LegResult>> futures = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            final int legIndex = i;
            final OrderRequest leg = legs.get(i);
            final ExecutionJournalEntity journal = journals.get(i);

            CompletableFuture<LegResult> future = CompletableFuture.supplyAsync(
                    () -> executeLeg(leg, journal, strategyId, priority, legIndex), parallelExecutor);
            futures.add(future);
        }

        // Step 3: Wait for all legs to complete
        List<LegResult> legResults =
                futures.stream().map(CompletableFuture::join).toList();

        // Step 4: Check for failures and roll back if needed
        boolean anyFailed = legResults.stream().anyMatch(r -> !r.isSuccess());
        if (anyFailed) {
            rollbackFilledLegs(legs, legResults, strategyId, priority);
        }

        MultiLegResult result = MultiLegResult.builder()
                .groupId(groupId)
                .success(!anyFailed)
                .legResults(legResults)
                .build();

        logDecision(strategyId, operationType, result);
        return result;
    }

    /**
     * Executes BUY legs first, waits for broker fills, then executes SELL legs.
     *
     * <p>This two-phase execution provides margin benefit: long positions from BUY fills
     * offset the margin requirement of subsequent SELL (short) positions. For example,
     * in a Bear Put Spread (BUY 25600PE + SELL 25400PE), the bought put reduces the
     * margin needed for the sold put.
     *
     * <p>If all legs are the same side (all BUY or all SELL), falls through to parallel
     * execution since phasing provides no margin benefit.
     *
     * <p>BUY fill confirmation uses {@link OrderFillTracker} which listens for
     * {@code OrderEvent.FILLED/REJECTED} events via Spring's event system.
     *
     * @param legs          all order requests (mixed BUY and SELL)
     * @param strategyId    the strategy this operation belongs to
     * @param operationType description of the operation (typically "ENTRY")
     * @param priority      the priority for all legs
     * @param fillTimeout   max time to wait for BUY fills before aborting
     * @return result containing success/failure status and details per leg
     */
    public MultiLegResult executeBuyFirstThenSell(
            List<OrderRequest> legs,
            String strategyId,
            String operationType,
            OrderPriority priority,
            Duration fillTimeout) {

        // Partition into BUY and SELL legs
        List<OrderRequest> buyLegs =
                legs.stream().filter(l -> l.getSide() == OrderSide.BUY).toList();
        List<OrderRequest> sellLegs =
                legs.stream().filter(l -> l.getSide() == OrderSide.SELL).toList();

        // If all same side, no phasing benefit — fall through to parallel
        if (buyLegs.isEmpty() || sellLegs.isEmpty()) {
            log.info(
                    "buyFirstThenSell: all legs are same side (buy={}, sell={}), falling through to parallel",
                    buyLegs.size(),
                    sellLegs.size());
            return executeParallel(legs, strategyId, operationType, priority);
        }

        String buyGroupId = UUID.randomUUID().toString();
        String sellGroupId = UUID.randomUUID().toString();

        log.info(
                "Starting buy-first-then-sell: strategy={}, operation={}, buyLegs={}, sellLegs={}, buyGroupId={}, sellGroupId={}",
                strategyId,
                operationType,
                buyLegs.size(),
                sellLegs.size(),
                buyGroupId,
                sellGroupId);

        // ---- Phase 1: Register fill await BEFORE routing (avoids race condition) ----
        CompletableFuture<Void> buyFillsFuture = orderFillTracker.awaitFills(buyGroupId, buyLegs.size());

        // Create journal entries for BUY phase
        List<ExecutionJournalEntity> buyJournals =
                createJournalEntries(buyLegs, strategyId, buyGroupId, operationType + "_BUY");

        // Execute BUY legs in parallel
        List<LegResult> buyResults = executeLegsConcurrently(buyLegs, buyJournals, strategyId, priority);

        boolean buyRoutingFailed = buyResults.stream().anyMatch(r -> !r.isSuccess());
        if (buyRoutingFailed) {
            // Cancel the fill await — we won't get fills for failed routes
            orderFillTracker.cancelAwait(buyGroupId);
            rollbackFilledLegs(buyLegs, buyResults, strategyId, priority);

            List<LegResult> allResults = new ArrayList<>(buyResults);
            // Mark SELL legs as skipped
            for (int i = 0; i < sellLegs.size(); i++) {
                allResults.add(LegResult.skipped(buyLegs.size() + i));
            }

            MultiLegResult result = MultiLegResult.builder()
                    .groupId(buyGroupId)
                    .success(false)
                    .legResults(allResults)
                    .build();
            logDecision(strategyId, operationType, result);
            return result;
        }

        // ---- Phase 2: Wait for BUY fills ----
        try {
            buyFillsFuture.get(fillTimeout.toMillis(), TimeUnit.MILLISECONDS);
            log.info("All BUY fills confirmed: buyGroupId={}", buyGroupId);
        } catch (TimeoutException e) {
            log.error(
                    "BUY fill timeout after {}s: buyGroupId={}, strategy={}. SELL legs NOT placed. BUY positions remain open for manual handling.",
                    fillTimeout.toSeconds(),
                    buyGroupId,
                    strategyId);

            List<LegResult> allResults = new ArrayList<>(buyResults);
            for (int i = 0; i < sellLegs.size(); i++) {
                allResults.add(LegResult.failed(
                        buyLegs.size() + i, "Skipped: BUY fill timeout after " + fillTimeout.toSeconds() + "s"));
            }

            MultiLegResult result = MultiLegResult.builder()
                    .groupId(buyGroupId)
                    .success(false)
                    .legResults(allResults)
                    .build();
            logDecision(strategyId, operationType, result);
            return result;
        } catch (Exception e) {
            // OrderFillTracker.OrderRejectedException or InterruptedException
            log.error("BUY phase failed: buyGroupId={}, strategy={}", buyGroupId, strategyId, e);

            List<LegResult> allResults = new ArrayList<>(buyResults);
            for (int i = 0; i < sellLegs.size(); i++) {
                allResults.add(LegResult.failed(buyLegs.size() + i, "Skipped: BUY phase failed - " + e.getMessage()));
            }

            MultiLegResult result = MultiLegResult.builder()
                    .groupId(buyGroupId)
                    .success(false)
                    .legResults(allResults)
                    .build();
            logDecision(strategyId, operationType, result);
            return result;
        }

        // ---- Phase 3: Execute SELL legs ----
        List<ExecutionJournalEntity> sellJournals =
                createJournalEntries(sellLegs, strategyId, sellGroupId, operationType + "_SELL");

        List<LegResult> sellResults = executeLegsConcurrently(sellLegs, sellJournals, strategyId, priority);

        boolean sellRoutingFailed = sellResults.stream().anyMatch(r -> !r.isSuccess());
        if (sellRoutingFailed) {
            // Roll back SELL legs only — BUY legs are filled and remain (manual cleanup)
            rollbackFilledLegs(sellLegs, sellResults, strategyId, priority);
            log.warn(
                    "SELL phase failed: sellGroupId={}, strategy={}. BUY positions remain open.",
                    sellGroupId,
                    strategyId);
        }

        // Combine results: BUY legs first, then SELL legs (reindex SELL legs)
        List<LegResult> allResults = new ArrayList<>(buyResults);
        for (LegResult sellResult : sellResults) {
            allResults.add(LegResult.builder()
                    .legIndex(buyLegs.size() + sellResult.getLegIndex())
                    .success(sellResult.isSuccess())
                    .tag(sellResult.getTag())
                    .failureReason(sellResult.getFailureReason())
                    .build());
        }

        MultiLegResult result = MultiLegResult.builder()
                .groupId(buyGroupId + "+" + sellGroupId)
                .success(!buyRoutingFailed && !sellRoutingFailed)
                .legResults(allResults)
                .build();

        logDecision(strategyId, operationType, result);
        return result;
    }

    /**
     * Executes a list of legs concurrently and returns their results.
     * Used internally by executeBuyFirstThenSell for each phase.
     */
    private List<LegResult> executeLegsConcurrently(
            List<OrderRequest> legs, List<ExecutionJournalEntity> journals, String strategyId, OrderPriority priority) {
        List<CompletableFuture<LegResult>> futures = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            final int legIndex = i;
            final OrderRequest leg = legs.get(i);
            final ExecutionJournalEntity journal = journals.get(i);

            CompletableFuture<LegResult> future = CompletableFuture.supplyAsync(
                    () -> executeLeg(leg, journal, strategyId, priority, legIndex), parallelExecutor);
            futures.add(future);
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    /**
     * Creates PENDING journal entries for all legs BEFORE any execution begins.
     * This is the write-ahead log: if the app crashes after this point,
     * recovery can see all planned legs and their current state.
     */
    List<ExecutionJournalEntity> createJournalEntries(
            List<OrderRequest> legs, String strategyId, String groupId, String operationType) {
        List<ExecutionJournalEntity> journals = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < legs.size(); i++) {
            OrderRequest leg = legs.get(i);
            ExecutionJournalEntity journal = ExecutionJournalEntity.builder()
                    .strategyId(strategyId)
                    .executionGroupId(groupId)
                    .operationType(operationType)
                    .legIndex(i)
                    .totalLegs(legs.size())
                    .instrumentToken(leg.getInstrumentToken())
                    .tradingSymbol(leg.getTradingSymbol())
                    .side(leg.getSide())
                    .quantity(leg.getQuantity())
                    .status(JournalStatus.PENDING)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            executionJournalJpaRepository.save(journal);
            journals.add(journal);
        }

        log.debug("Created {} PENDING journal entries for group {}", legs.size(), groupId);
        return journals;
    }

    /**
     * Executes a single leg: routes the order and updates the journal.
     */
    private LegResult executeLeg(
            OrderRequest leg, ExecutionJournalEntity journal, String strategyId, OrderPriority priority, int legIndex) {
        // Update journal to IN_PROGRESS
        updateJournalStatus(journal, JournalStatus.IN_PROGRESS, null);

        try {
            // Generate tag for this leg
            String tag = orderTagGenerator.generate(strategyId, priority);
            leg.setCorrelationId(journal.getExecutionGroupId());

            // Route through OrderRouter (validates + enqueues)
            OrderRouteResult routeResult = orderRouter.route(leg, priority);

            if (!routeResult.isAccepted()) {
                updateJournalStatus(journal, JournalStatus.FAILED, routeResult.getRejectionReason());
                log.warn(
                        "Leg {} failed routing: groupId={}, reason={}",
                        legIndex,
                        journal.getExecutionGroupId(),
                        routeResult.getRejectionReason());
                return LegResult.failed(legIndex, routeResult.getRejectionReason());
            }

            // Mark journal as completed
            updateJournalStatus(journal, JournalStatus.COMPLETED, null);

            log.info(
                    "Leg {} executed: groupId={}, symbol={}, tag={}",
                    legIndex,
                    journal.getExecutionGroupId(),
                    leg.getTradingSymbol(),
                    tag);

            return LegResult.success(legIndex, tag);

        } catch (Exception e) {
            updateJournalStatus(journal, JournalStatus.FAILED, e.getMessage());
            log.error("Leg {} execution error: groupId={}", legIndex, journal.getExecutionGroupId(), e);
            return LegResult.failed(legIndex, e.getMessage());
        }
    }

    /**
     * Rolls back all successfully filled legs by placing opposite orders.
     *
     * <p>For example, if leg 0 (BUY CE) and leg 1 (BUY PE) succeeded but
     * leg 2 (SELL CE) failed, this method places SELL orders for legs 0 and 1
     * to unwind the positions.
     */
    void rollbackFilledLegs(
            List<OrderRequest> originalLegs, List<LegResult> legResults, String strategyId, OrderPriority priority) {
        log.warn("Rolling back filled legs for strategy {}", strategyId);

        for (int i = 0; i < legResults.size(); i++) {
            LegResult result = legResults.get(i);
            if (result.isSuccess()) {
                OrderRequest originalLeg = originalLegs.get(i);
                OrderRequest rollbackOrder = OrderRequest.builder()
                        .instrumentToken(originalLeg.getInstrumentToken())
                        .tradingSymbol(originalLeg.getTradingSymbol())
                        .exchange(originalLeg.getExchange())
                        .side(originalLeg.getSide().opposite())
                        .type(originalLeg.getType())
                        .product(originalLeg.getProduct())
                        .quantity(originalLeg.getQuantity())
                        .price(originalLeg.getPrice())
                        .triggerPrice(originalLeg.getTriggerPrice())
                        .strategyId(strategyId)
                        .correlationId("ROLLBACK-" + result.getTag())
                        .build();

                OrderRouteResult rollbackResult = orderRouter.route(rollbackOrder, priority);
                if (rollbackResult.isAccepted()) {
                    log.info("Rollback leg {} successful: symbol={}", i, originalLeg.getTradingSymbol());
                } else {
                    log.error(
                            "Rollback leg {} FAILED: symbol={}, reason={}",
                            i,
                            originalLeg.getTradingSymbol(),
                            rollbackResult.getRejectionReason());
                }
            }
        }
    }

    private void updateJournalStatus(ExecutionJournalEntity journal, JournalStatus status, String failureReason) {
        journal.setStatus(status);
        journal.setFailureReason(failureReason);
        journal.setUpdatedAt(LocalDateTime.now());
        executionJournalJpaRepository.save(journal);
    }

    private void logDecision(String strategyId, String operationType, MultiLegResult result) {
        long successCount =
                result.getLegResults().stream().filter(LegResult::isSuccess).count();
        String message = String.format(
                "Multi-leg %s %s: %d/%d legs succeeded [groupId=%s]",
                operationType,
                result.isSuccess() ? "completed" : "FAILED (rolled back)",
                successCount,
                result.getLegResults().size(),
                result.getGroupId());

        eventPublisherHelper.publishDecision(
                this,
                "ORDER",
                message,
                strategyId,
                Map.of(
                        "groupId", result.getGroupId(),
                        "operationType", operationType,
                        "success", result.isSuccess(),
                        "totalLegs", result.getLegResults().size(),
                        "successfulLegs", successCount));
    }

    /** Result of a multi-leg operation. */
    @lombok.Data
    @lombok.Builder
    public static class MultiLegResult {
        private String groupId;
        private boolean success;
        private List<LegResult> legResults;
    }

    /** Result of a single leg within a multi-leg operation. */
    @lombok.Data
    @lombok.Builder
    public static class LegResult {
        private int legIndex;
        private boolean success;
        private String tag;
        private String failureReason;

        public static LegResult success(int legIndex, String tag) {
            return LegResult.builder().legIndex(legIndex).success(true).tag(tag).build();
        }

        public static LegResult failed(int legIndex, String reason) {
            return LegResult.builder()
                    .legIndex(legIndex)
                    .success(false)
                    .failureReason(reason)
                    .build();
        }

        public static LegResult skipped(int legIndex) {
            return LegResult.builder()
                    .legIndex(legIndex)
                    .success(false)
                    .failureReason("Skipped due to prior leg failure")
                    .build();
        }
    }
}
