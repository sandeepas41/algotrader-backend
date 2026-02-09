package com.algotrader.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.config.RedisConfig;
import com.algotrader.entity.AuditLogEntity;
import com.algotrader.entity.DeadLetterEventEntity;
import com.algotrader.entity.TradeEntity;
import com.algotrader.repository.jpa.AuditLogJpaRepository;
import com.algotrader.repository.jpa.DeadLetterEventJpaRepository;
import com.algotrader.repository.jpa.TradeJpaRepository;
import com.algotrader.service.DataSyncService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit tests for DataSyncService covering write-behind batching, queue overflow,
 * dead letter queue ordering, daily P&L persistence, and flush-all behavior.
 */
class DataSyncServiceTest {

    private TradeJpaRepository tradeJpaRepository;
    private AuditLogJpaRepository auditLogJpaRepository;
    private DeadLetterEventJpaRepository deadLetterEventJpaRepository;
    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOperations;
    private DataSyncService dataSyncService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        tradeJpaRepository = mock(TradeJpaRepository.class);
        auditLogJpaRepository = mock(AuditLogJpaRepository.class);
        deadLetterEventJpaRepository = mock(DeadLetterEventJpaRepository.class);
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // Default: normal capacity queues
        dataSyncService = new DataSyncService(
                tradeJpaRepository, auditLogJpaRepository, deadLetterEventJpaRepository, redisTemplate);
    }

    /** Creates a DataSyncService with small queue capacity for overflow testing. */
    private DataSyncService createWithSmallQueues(int tradeCapacity, int auditCapacity) {
        return new DataSyncService(
                tradeJpaRepository,
                auditLogJpaRepository,
                deadLetterEventJpaRepository,
                redisTemplate,
                tradeCapacity,
                auditCapacity);
    }

    @Nested
    @DisplayName("Trade Queue")
    class TradeQueue {

        @Test
        @DisplayName("Queued trade is flushed on flushTrades")
        void queuedTradeIsFlushed() {
            dataSyncService.queueTrade(TradeEntity.builder().id("T1").build());

            dataSyncService.flushTrades();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TradeEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(tradeJpaRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().get(0).getId()).isEqualTo("T1");
        }

        @Test
        @DisplayName("Empty queue does not trigger saveAll")
        void emptyQueueNoSave() {
            dataSyncService.flushTrades();

            verify(tradeJpaRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("Multiple trades are batched in a single flush")
        void multipleTradesBatched() {
            dataSyncService.queueTrade(TradeEntity.builder().id("T1").build());
            dataSyncService.queueTrade(TradeEntity.builder().id("T2").build());
            dataSyncService.queueTrade(TradeEntity.builder().id("T3").build());

            dataSyncService.flushTrades();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TradeEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(tradeJpaRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).hasSize(3);
        }

        @Test
        @DisplayName("Flush failure sends entries to dead letter queue")
        void flushFailureSendsToDeadLetter() {
            dataSyncService.queueTrade(TradeEntity.builder().id("T1").build());
            doThrow(new RuntimeException("DB down")).when(tradeJpaRepository).saveAll(any());

            dataSyncService.flushTrades();

            verify(deadLetterEventJpaRepository).save(any(DeadLetterEventEntity.class));
        }

        @Test
        @DisplayName("Queue overflow triggers synchronous write")
        void queueOverflowTriggersSynchronousWrite() {
            DataSyncService smallQueue = createWithSmallQueues(1, 100);
            smallQueue.queueTrade(TradeEntity.builder().id("T1").build()); // fills the queue

            // This one should overflow to synchronous write
            smallQueue.queueTrade(TradeEntity.builder().id("T2").build());

            verify(tradeJpaRepository).save(any(TradeEntity.class));
        }
    }

    @Nested
    @DisplayName("Audit Queue")
    class AuditQueue {

        @Test
        @DisplayName("Queued audit log is flushed on flushAuditLogs")
        void queuedAuditLogIsFlushed() {
            AuditLogEntity auditLogEntity = AuditLogEntity.builder()
                    .eventType("ORDER_PLACED")
                    .entityType("Order")
                    .entityId("O1")
                    .build();
            dataSyncService.queueAuditLog(auditLogEntity);

            dataSyncService.flushAuditLogs();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<AuditLogEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(auditLogJpaRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
        }

        @Test
        @DisplayName("Empty audit queue does not trigger saveAll")
        void emptyAuditQueueNoSave() {
            dataSyncService.flushAuditLogs();

            verify(auditLogJpaRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("Audit queue overflow triggers synchronous write")
        void auditQueueOverflowTriggersSynchronousWrite() {
            DataSyncService smallQueue = createWithSmallQueues(100, 1);
            smallQueue.queueAuditLog(AuditLogEntity.builder()
                    .eventType("E1")
                    .entityType("T1")
                    .entityId("ID1")
                    .build());

            // This one should overflow
            smallQueue.queueAuditLog(AuditLogEntity.builder()
                    .eventType("E2")
                    .entityType("T2")
                    .entityId("ID2")
                    .build());

            verify(auditLogJpaRepository).save(any(AuditLogEntity.class));
        }
    }

    @Nested
    @DisplayName("Dead Letter Queue Ordering")
    class DeadLetterQueueOrdering {

        @Test
        @DisplayName("Dead letter entries have monotonically increasing sequence numbers")
        void deadLetterSequenceIncreases() {
            doThrow(new RuntimeException("DB down")).when(tradeJpaRepository).saveAll(any());

            dataSyncService.queueTrade(TradeEntity.builder().id("T1").build());
            dataSyncService.flushTrades();
            reset(tradeJpaRepository);
            doThrow(new RuntimeException("DB down")).when(tradeJpaRepository).saveAll(any());

            dataSyncService.queueTrade(TradeEntity.builder().id("T2").build());
            dataSyncService.flushTrades();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<DeadLetterEventEntity> captor = ArgumentCaptor.forClass(DeadLetterEventEntity.class);
            verify(deadLetterEventJpaRepository, times(2)).save(captor.capture());

            List<DeadLetterEventEntity> entries = captor.getAllValues();
            assertThat(entries.get(0).getPayload()).contains("\"sequence\":1");
            assertThat(entries.get(1).getPayload()).contains("\"sequence\":2");
        }

        @Test
        @DisplayName("Dead letter entry has correct event type and status PENDING")
        void deadLetterEntryCorrectFields() {
            doThrow(new RuntimeException("DB down")).when(tradeJpaRepository).saveAll(any());

            dataSyncService.queueTrade(TradeEntity.builder().id("T1").build());
            dataSyncService.flushTrades();

            ArgumentCaptor<DeadLetterEventEntity> captor = ArgumentCaptor.forClass(DeadLetterEventEntity.class);
            verify(deadLetterEventJpaRepository).save(captor.capture());

            DeadLetterEventEntity entry = captor.getValue();
            assertThat(entry.getEventType()).isEqualTo("TRADE_FLUSH");
            assertThat(entry.getStatus()).isEqualTo("PENDING");
            assertThat(entry.getRetryCount()).isEqualTo(0);
            assertThat(entry.getMaxRetries()).isEqualTo(3);
            assertThat(entry.getErrorMessage()).isEqualTo("DB down");
            assertThat(entry.getStackTrace()).contains("RuntimeException");
        }
    }

    @Nested
    @DisplayName("Daily P&L Persistence")
    class DailyPnlPersistence {

        @Test
        @DisplayName("persistDailyPnl writes to Redis with correct key and TTL")
        void persistDailyPnlWritesToRedis() {
            BigDecimal pnl = new BigDecimal("15000.50");

            dataSyncService.persistDailyPnl(pnl);

            String expectedKey = RedisConfig.KEY_PREFIX_DAILY_PNL + LocalDate.now();
            verify(valueOperations).set(eq(expectedKey), eq("15000.50"), eq(Duration.ofHours(24)));
        }

        @Test
        @DisplayName("restoreDailyPnl reads from Redis and returns BigDecimal")
        void restoreDailyPnlReadsFromRedis() {
            String expectedKey = RedisConfig.KEY_PREFIX_DAILY_PNL + LocalDate.now();
            when(valueOperations.get(expectedKey)).thenReturn("7500.25");

            BigDecimal pnl = dataSyncService.restoreDailyPnl();

            assertThat(pnl).isEqualByComparingTo("7500.25");
        }

        @Test
        @DisplayName("restoreDailyPnl returns ZERO when Redis key is missing")
        void restoreDailyPnlReturnsZeroWhenMissing() {
            String expectedKey = RedisConfig.KEY_PREFIX_DAILY_PNL + LocalDate.now();
            when(valueOperations.get(expectedKey)).thenReturn(null);

            BigDecimal pnl = dataSyncService.restoreDailyPnl();

            assertThat(pnl).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("Flush All (Shutdown)")
    class FlushAll {

        @Test
        @DisplayName("flushAll drains both queues")
        void flushAllDrainsBothQueues() {
            dataSyncService.queueTrade(TradeEntity.builder().id("T1").build());
            dataSyncService.queueAuditLog(AuditLogEntity.builder()
                    .eventType("E1")
                    .entityType("T1")
                    .entityId("ID1")
                    .build());

            dataSyncService.flushAll();

            verify(tradeJpaRepository).saveAll(any());
            verify(auditLogJpaRepository).saveAll(any());
        }

        @Test
        @DisplayName("flushAll with empty queues does not call saveAll")
        void flushAllEmptyQueuesNoSave() {
            dataSyncService.flushAll();

            verify(tradeJpaRepository, never()).saveAll(any());
            verify(auditLogJpaRepository, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("Queue Metrics")
    class QueueMetrics {

        @Test
        @DisplayName("Queue sizes reflect pending entries")
        void queueSizesReflectPendingEntries() {
            assertThat(dataSyncService.getTradeQueueSize()).isEqualTo(0);
            assertThat(dataSyncService.getAuditQueueSize()).isEqualTo(0);

            dataSyncService.queueTrade(TradeEntity.builder().id("T1").build());
            dataSyncService.queueTrade(TradeEntity.builder().id("T2").build());
            dataSyncService.queueAuditLog(AuditLogEntity.builder()
                    .eventType("E1")
                    .entityType("T1")
                    .entityId("ID1")
                    .build());

            assertThat(dataSyncService.getTradeQueueSize()).isEqualTo(2);
            assertThat(dataSyncService.getAuditQueueSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("Queue size is zero after flush")
        void queueSizeZeroAfterFlush() {
            dataSyncService.queueTrade(TradeEntity.builder().id("T1").build());
            dataSyncService.flushTrades();

            assertThat(dataSyncService.getTradeQueueSize()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Synchronous Write Fallback")
    class SynchronousWriteFallback {

        @Test
        @DisplayName("Synchronous trade write failure goes to dead letter queue")
        void syncTradeWriteFailureGoesToDeadLetter() {
            DataSyncService smallQueue = createWithSmallQueues(1, 100);
            smallQueue.queueTrade(TradeEntity.builder().id("T1").build()); // fills queue

            // Synchronous save fails too
            doThrow(new RuntimeException("Disk full")).when(tradeJpaRepository).save(any(TradeEntity.class));

            smallQueue.queueTrade(TradeEntity.builder().id("T2").build()); // overflow + sync fail

            verify(deadLetterEventJpaRepository).save(any(DeadLetterEventEntity.class));
        }

        @Test
        @DisplayName("Synchronous audit write failure goes to dead letter queue")
        void syncAuditWriteFailureGoesToDeadLetter() {
            DataSyncService smallQueue = createWithSmallQueues(100, 1);
            smallQueue.queueAuditLog(AuditLogEntity.builder()
                    .eventType("E1")
                    .entityType("T1")
                    .entityId("ID1")
                    .build());

            doThrow(new RuntimeException("Disk full"))
                    .when(auditLogJpaRepository)
                    .save(any(AuditLogEntity.class));

            smallQueue.queueAuditLog(AuditLogEntity.builder()
                    .eventType("E2")
                    .entityType("T2")
                    .entityId("ID2")
                    .build());

            verify(deadLetterEventJpaRepository).save(any(DeadLetterEventEntity.class));
        }
    }
}
