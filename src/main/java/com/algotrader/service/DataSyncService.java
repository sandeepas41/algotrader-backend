package com.algotrader.service;

import com.algotrader.config.RedisConfig;
import com.algotrader.entity.AuditLogEntity;
import com.algotrader.entity.DeadLetterEventEntity;
import com.algotrader.entity.TradeEntity;
import com.algotrader.repository.jpa.AuditLogJpaRepository;
import com.algotrader.repository.jpa.DeadLetterEventJpaRepository;
import com.algotrader.repository.jpa.TradeJpaRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Write-behind data sync service that batches writes to H2 for performance.
 *
 * <p>Trading is latency-sensitive, so this service decouples persistence from the
 * hot path by buffering entities in in-memory queues and flushing them on a schedule:
 * <ul>
 *   <li>Trade records: flush every 1s (infrequent but critical; the 5-10s window
 *       risks losing trade records on JVM crash since positions are recoverable
 *       from Redis via reconciliation, but trade records would be lost from H2)</li>
 *   <li>Audit logs: flush every 10s (high volume, less time-critical)</li>
 * </ul>
 *
 * <p>When a queue is full, the service overflows to a synchronous DB write rather than
 * dropping data. If the synchronous write also fails, the entry is sent to the dead
 * letter queue for later reprocessing.
 *
 * <p>Dead letter entries carry a monotonically increasing sequence number so that
 * dependent events (e.g., OrderEvent.PLACED before OrderEvent.FILLED) maintain
 * correct ordering when reprocessed.
 *
 * <p>Daily P&L running total is persisted to Redis on every P&L change event, so
 * crash recovery doesn't reset the daily loss limit counter to zero.
 */
@Service
public class DataSyncService {

    private static final Logger log = LoggerFactory.getLogger(DataSyncService.class);

    private static final int TRADE_QUEUE_CAPACITY = 10_000;
    private static final int AUDIT_QUEUE_CAPACITY = 50_000;
    private static final int MAX_BATCH_SIZE_TRADES = 500;
    private static final int MAX_BATCH_SIZE_AUDIT = 1_000;
    private static final int DEAD_LETTER_MAX_RETRIES = 3;

    /** Monotonically increasing counter for dead letter entry ordering. */
    private final AtomicLong deadLetterSequence = new AtomicLong(0);

    private final BlockingQueue<TradeEntity> tradeQueue;
    private final BlockingQueue<AuditLogEntity> auditQueue;

    private final TradeJpaRepository tradeJpaRepository;
    private final AuditLogJpaRepository auditLogJpaRepository;
    private final DeadLetterEventJpaRepository deadLetterEventJpaRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    public DataSyncService(
            TradeJpaRepository tradeJpaRepository,
            AuditLogJpaRepository auditLogJpaRepository,
            DeadLetterEventJpaRepository deadLetterEventJpaRepository,
            RedisTemplate<String, Object> redisTemplate) {
        this(
                tradeJpaRepository,
                auditLogJpaRepository,
                deadLetterEventJpaRepository,
                redisTemplate,
                TRADE_QUEUE_CAPACITY,
                AUDIT_QUEUE_CAPACITY);
    }

    /** Secondary constructor for tests that need small queue capacities. */
    public DataSyncService(
            TradeJpaRepository tradeJpaRepository,
            AuditLogJpaRepository auditLogJpaRepository,
            DeadLetterEventJpaRepository deadLetterEventJpaRepository,
            RedisTemplate<String, Object> redisTemplate,
            int tradeQueueCapacity,
            int auditQueueCapacity) {
        this.tradeJpaRepository = tradeJpaRepository;
        this.auditLogJpaRepository = auditLogJpaRepository;
        this.deadLetterEventJpaRepository = deadLetterEventJpaRepository;
        this.redisTemplate = redisTemplate;
        this.tradeQueue = new LinkedBlockingQueue<>(tradeQueueCapacity);
        this.auditQueue = new LinkedBlockingQueue<>(auditQueueCapacity);
    }

    // ---- Queue methods (called from hot path) ----

    /**
     * Queues a trade for write-behind persistence to H2.
     * If the queue is full, falls back to synchronous DB write to avoid data loss.
     */
    public void queueTrade(TradeEntity tradeEntity) {
        if (!tradeQueue.offer(tradeEntity)) {
            log.warn(
                    "Trade queue full ({} entries), forcing synchronous write for trade {}",
                    TRADE_QUEUE_CAPACITY,
                    tradeEntity.getId());
            writeTradeSynchronously(tradeEntity);
        }
    }

    /**
     * Queues an audit log for write-behind persistence to H2.
     * If the queue is full, falls back to synchronous DB write.
     */
    public void queueAuditLog(AuditLogEntity auditLogEntity) {
        if (!auditQueue.offer(auditLogEntity)) {
            log.warn("Audit queue full ({} entries), forcing synchronous write", AUDIT_QUEUE_CAPACITY);
            writeAuditLogSynchronously(auditLogEntity);
        }
    }

    // ---- Scheduled flush methods ----

    /**
     * Flushes trade records every 1 second.
     * Trade records are infrequent but critical -- a JVM crash during the flush window
     * would lose them from H2 (positions are recoverable from Redis via reconciliation).
     */
    @Scheduled(fixedRate = 1_000)
    public void flushTrades() {
        List<TradeEntity> batch = new ArrayList<>();
        tradeQueue.drainTo(batch, MAX_BATCH_SIZE_TRADES);

        if (!batch.isEmpty()) {
            try {
                tradeJpaRepository.saveAll(batch);
                log.debug("Flushed {} trades to H2", batch.size());
            } catch (Exception e) {
                log.error("Failed to flush {} trades to H2, sending to dead letter queue", batch.size(), e);
                for (TradeEntity tradeEntity : batch) {
                    sendToDeadLetterQueue("TRADE_FLUSH", tradeEntity.getId(), e);
                }
            }
        }
    }

    /**
     * Flushes audit logs every 10 seconds.
     * High volume, less time-critical than trades.
     */
    @Scheduled(fixedRate = 10_000)
    public void flushAuditLogs() {
        List<AuditLogEntity> batch = new ArrayList<>();
        auditQueue.drainTo(batch, MAX_BATCH_SIZE_AUDIT);

        if (!batch.isEmpty()) {
            try {
                auditLogJpaRepository.saveAll(batch);
                log.debug("Flushed {} audit logs to H2", batch.size());
            } catch (Exception e) {
                log.error("Failed to flush {} audit logs to H2, sending to dead letter queue", batch.size(), e);
                for (AuditLogEntity auditLogEntity : batch) {
                    sendToDeadLetterQueue(
                            "AUDIT_FLUSH", auditLogEntity.getEntityType() + ":" + auditLogEntity.getEntityId(), e);
                }
            }
        }
    }

    // ---- Daily P&L persistence ----

    /**
     * Persists the daily P&L running total to Redis. Called on every P&L change event
     * so that crash recovery doesn't reset the daily loss limit counter to zero.
     *
     * <p>Key: algo:daily:pnl:{date}, TTL: 24 hours.
     */
    public void persistDailyPnl(BigDecimal dailyPnl) {
        String key = RedisConfig.KEY_PREFIX_DAILY_PNL + LocalDate.now();
        redisTemplate.opsForValue().set(key, dailyPnl.toPlainString(), Duration.ofHours(24));
        log.debug("Persisted daily P&L to Redis: {}", dailyPnl);
    }

    /**
     * Restores the daily P&L running total from Redis (called on startup recovery).
     *
     * @return the stored daily P&L, or {@link BigDecimal#ZERO} if not found
     */
    public BigDecimal restoreDailyPnl() {
        String key = RedisConfig.KEY_PREFIX_DAILY_PNL + LocalDate.now();
        Object stored = redisTemplate.opsForValue().get(key);
        if (stored != null) {
            BigDecimal pnl = new BigDecimal(stored.toString());
            log.info("Restored daily P&L from Redis: {}", pnl);
            return pnl;
        }
        return BigDecimal.ZERO;
    }

    // ---- Flush all (called during graceful shutdown) ----

    /**
     * Flushes all pending data to H2. Called by GracefulShutdownService
     * to ensure no data is lost when the JVM exits.
     */
    public void flushAll() {
        log.info("Flushing all queued data to H2...");

        List<TradeEntity> trades = new ArrayList<>();
        tradeQueue.drainTo(trades);
        if (!trades.isEmpty()) {
            try {
                tradeJpaRepository.saveAll(trades);
                log.info("Flushed {} pending trades during shutdown", trades.size());
            } catch (Exception e) {
                log.error("Failed to flush trades during shutdown", e);
            }
        }

        List<AuditLogEntity> audits = new ArrayList<>();
        auditQueue.drainTo(audits);
        if (!audits.isEmpty()) {
            try {
                auditLogJpaRepository.saveAll(audits);
                log.info("Flushed {} pending audit logs during shutdown", audits.size());
            } catch (Exception e) {
                log.error("Failed to flush audit logs during shutdown", e);
            }
        }
    }

    // ---- Queue metrics (for monitoring) ----

    /** Returns the current number of pending trade records in the queue. */
    public int getTradeQueueSize() {
        return tradeQueue.size();
    }

    /** Returns the current number of pending audit logs in the queue. */
    public int getAuditQueueSize() {
        return auditQueue.size();
    }

    // ---- Dead letter queue ----

    /**
     * Sends a failed data entry to the dead letter queue with a sequence number
     * for correct ordering during reprocessing. Dependent events (e.g.,
     * OrderEvent.PLACED before OrderEvent.FILLED) maintain ordering via the
     * monotonically increasing sequence.
     */
    private void sendToDeadLetterQueue(String eventType, String entityId, Exception exception) {
        try {
            long sequence = deadLetterSequence.incrementAndGet();
            DeadLetterEventEntity deadLetterEventEntity = DeadLetterEventEntity.builder()
                    .eventType(eventType)
                    // Encode sequence in payload for ordered reprocessing
                    .payload("{\"entityId\":\"" + entityId + "\",\"sequence\":" + sequence + "}")
                    .errorMessage(exception.getMessage())
                    .stackTrace(truncateStackTrace(exception))
                    .retryCount(0)
                    .maxRetries(DEAD_LETTER_MAX_RETRIES)
                    .status("PENDING")
                    .createdAt(LocalDateTime.now())
                    .build();
            deadLetterEventJpaRepository.save(deadLetterEventEntity);
            log.warn("Sent {} (entity={}) to dead letter queue with sequence {}", eventType, entityId, sequence);
        } catch (Exception e) {
            // Last resort: log the failure (we cannot lose the dead letter entry itself)
            log.error(
                    "CRITICAL: Failed to write to dead letter queue for {} (entity={}): {}",
                    eventType,
                    entityId,
                    e.getMessage());
        }
    }

    // ---- Private helpers ----

    private void writeTradeSynchronously(TradeEntity tradeEntity) {
        try {
            tradeJpaRepository.save(tradeEntity);
        } catch (Exception e) {
            log.error("Synchronous trade write failed for {}, sending to dead letter queue", tradeEntity.getId(), e);
            sendToDeadLetterQueue("TRADE_SYNC", tradeEntity.getId(), e);
        }
    }

    private void writeAuditLogSynchronously(AuditLogEntity auditLogEntity) {
        try {
            auditLogJpaRepository.save(auditLogEntity);
        } catch (Exception e) {
            log.error("Synchronous audit log write failed, sending to dead letter queue", e);
            sendToDeadLetterQueue("AUDIT_SYNC", auditLogEntity.getEntityType() + ":" + auditLogEntity.getEntityId(), e);
        }
    }

    private String truncateStackTrace(Exception exception) {
        StringBuilder sb = new StringBuilder();
        sb.append(exception.getClass().getName())
                .append(": ")
                .append(exception.getMessage())
                .append("\n");
        StackTraceElement[] trace = exception.getStackTrace();
        int limit = Math.min(trace.length, 10);
        for (int i = 0; i < limit; i++) {
            sb.append("\tat ").append(trace[i]).append("\n");
        }
        if (trace.length > limit) {
            sb.append("\t... ").append(trace.length - limit).append(" more\n");
        }
        return sb.toString();
    }
}
