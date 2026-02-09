package com.algotrader.observability;

import com.algotrader.domain.enums.DecisionSeverity;
import com.algotrader.domain.model.DecisionRecord;
import com.algotrader.entity.DecisionLogEntity;
import com.algotrader.mapper.DecisionLogMapper;
import com.algotrader.repository.jpa.DecisionLogJpaRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.mapstruct.factory.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Async batch persistence of decision records to H2 with circuit breaker protection.
 *
 * <p>Decision records are queued by the {@code DecisionLogger} and flushed to H2
 * in batches on a fixed schedule (every 5 seconds). This decouples the trading path
 * from database I/O -- persistence failures never block order execution.
 *
 * <p>Circuit breaker: after {@value #FAILURE_THRESHOLD} consecutive persistence failures,
 * the circuit opens and stops attempting H2 writes until manually reset or
 * {@value #RECOVERY_INTERVAL_MS}ms elapses. This prevents log storms when H2
 * is under pressure.
 *
 * <p>The persist-debug toggle controls whether DEBUG-severity entries are queued
 * for persistence. By default, only INFO+ entries are persisted to avoid flooding
 * H2 with routine evaluations.
 */
@Service
public class DecisionArchiveService {

    private static final Logger log = LoggerFactory.getLogger(DecisionArchiveService.class);

    private static final int FAILURE_THRESHOLD = 3;
    private static final long RECOVERY_INTERVAL_MS = 60_000;
    private static final int MAX_BATCH_SIZE = 200;

    private final DecisionLogJpaRepository decisionLogJpaRepository;
    private final DecisionLogMapper decisionLogMapper = Mappers.getMapper(DecisionLogMapper.class);

    private final ConcurrentLinkedQueue<DecisionRecord> pendingQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicBoolean circuitOpen = new AtomicBoolean(false);
    private volatile long circuitOpenedAt = 0;

    /** When true, DEBUG-severity entries are also persisted. Default: false. */
    private volatile boolean persistDebug = false;

    public DecisionArchiveService(DecisionLogJpaRepository decisionLogJpaRepository) {
        this.decisionLogJpaRepository = decisionLogJpaRepository;
    }

    /**
     * Queues a decision record for async batch persistence.
     *
     * <p>DEBUG-severity records are only queued if {@link #isPersistDebug()} is true.
     * Records are never dropped from the queue (only skipped when circuit is open).
     */
    public void queue(DecisionRecord decisionRecord) {
        // Skip DEBUG entries unless persist-debug is enabled
        if (decisionRecord.getSeverity() == DecisionSeverity.DEBUG && !persistDebug) {
            return;
        }

        pendingQueue.add(decisionRecord);
    }

    /**
     * Flushes pending decision records to H2 in batch.
     *
     * <p>Runs every 5 seconds via @Scheduled. If the circuit breaker is open,
     * checks whether the recovery interval has elapsed before attempting.
     */
    @Scheduled(fixedRate = 5000)
    public void flush() {
        if (pendingQueue.isEmpty()) {
            return;
        }

        // Circuit breaker: check if recovery interval has passed
        if (circuitOpen.get()) {
            if (System.currentTimeMillis() - circuitOpenedAt < RECOVERY_INTERVAL_MS) {
                log.debug("Circuit open, skipping flush. {} records queued.", pendingQueue.size());
                return;
            }
            // Attempt recovery
            log.info("Circuit breaker recovery attempt. {} records queued.", pendingQueue.size());
        }

        // Drain up to MAX_BATCH_SIZE records
        List<DecisionRecord> batch = new ArrayList<>(MAX_BATCH_SIZE);
        for (int i = 0; i < MAX_BATCH_SIZE; i++) {
            DecisionRecord record = pendingQueue.poll();
            if (record == null) {
                break;
            }
            batch.add(record);
        }

        if (batch.isEmpty()) {
            return;
        }

        try {
            List<DecisionLogEntity> entities = decisionLogMapper.toEntityList(batch);
            decisionLogJpaRepository.saveAll(entities);

            // Success: reset circuit breaker
            if (circuitOpen.compareAndSet(true, false)) {
                log.info("Circuit breaker closed after successful flush");
            }
            consecutiveFailures.set(0);

            log.debug("Flushed {} decision records to H2", batch.size());

        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            log.error(
                    "Failed to persist {} decision records (failure {}/{}): {}",
                    batch.size(),
                    failures,
                    FAILURE_THRESHOLD,
                    e.getMessage());

            if (failures >= FAILURE_THRESHOLD && circuitOpen.compareAndSet(false, true)) {
                circuitOpenedAt = System.currentTimeMillis();
                log.warn(
                        "Circuit breaker opened after {} consecutive failures. "
                                + "Will retry after {}ms. {} records in queue.",
                        failures,
                        RECOVERY_INTERVAL_MS,
                        pendingQueue.size());
            }

            // Re-queue failed batch at front for retry (they'll be picked up next flush)
            // Note: ConcurrentLinkedQueue doesn't support addFirst, so add to tail
            pendingQueue.addAll(batch);
        }
    }

    /**
     * Forces an immediate flush, bypassing the circuit breaker.
     * Used during application shutdown to persist remaining records.
     */
    @Async("eventExecutor")
    public void forceFlush() {
        log.info("Force flushing {} pending decision records", pendingQueue.size());
        boolean wasCircuitOpen = circuitOpen.getAndSet(false);
        consecutiveFailures.set(0);

        flush();

        if (wasCircuitOpen) {
            log.info("Circuit breaker was reset during force flush");
        }
    }

    // ---- Persist-debug toggle ----

    public boolean isPersistDebug() {
        return persistDebug;
    }

    /**
     * Enables or disables persistence of DEBUG-severity entries.
     * Can be toggled at runtime via REST API or actuator endpoint.
     */
    public void setPersistDebug(boolean persistDebug) {
        this.persistDebug = persistDebug;
        log.info("Persist-debug toggled to: {}", persistDebug);
    }

    // ---- Circuit breaker state (for monitoring) ----

    public boolean isCircuitOpen() {
        return circuitOpen.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public int getPendingCount() {
        return pendingQueue.size();
    }

    /**
     * Manually resets the circuit breaker, allowing persistence to resume immediately.
     */
    public void resetCircuitBreaker() {
        circuitOpen.set(false);
        consecutiveFailures.set(0);
        log.info("Circuit breaker manually reset");
    }
}
