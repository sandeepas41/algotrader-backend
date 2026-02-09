package com.algotrader.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.algotrader.domain.enums.DecisionOutcome;
import com.algotrader.domain.enums.DecisionSeverity;
import com.algotrader.domain.enums.DecisionSource;
import com.algotrader.domain.enums.DecisionType;
import com.algotrader.domain.model.DecisionRecord;
import com.algotrader.observability.DecisionArchiveService;
import com.algotrader.repository.jpa.DecisionLogJpaRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DecisionArchiveService.
 *
 * <p>Verifies async batch persistence, circuit breaker behavior,
 * persist-debug toggle, and queue management.
 */
class DecisionArchiveServiceTest {

    private DecisionLogJpaRepository decisionLogJpaRepository;
    private DecisionArchiveService decisionArchiveService;

    @BeforeEach
    void setUp() {
        decisionLogJpaRepository = mock(DecisionLogJpaRepository.class);
        decisionArchiveService = new DecisionArchiveService(decisionLogJpaRepository);
    }

    private DecisionRecord createRecord(DecisionSeverity severity) {
        return DecisionRecord.builder()
                .timestamp(LocalDateTime.now())
                .source(DecisionSource.STRATEGY_ENGINE)
                .sourceId("STR-001")
                .decisionType(DecisionType.STRATEGY_ENTRY_TRIGGERED)
                .outcome(DecisionOutcome.TRIGGERED)
                .reasoning("Test record")
                .severity(severity)
                .sessionDate(LocalDate.now())
                .build();
    }

    @Nested
    @DisplayName("Queue and flush")
    class QueueAndFlush {

        @Test
        @DisplayName("flush persists queued records to H2")
        void flushPersistsQueuedRecords() {
            decisionArchiveService.queue(createRecord(DecisionSeverity.INFO));
            decisionArchiveService.queue(createRecord(DecisionSeverity.WARNING));
            decisionArchiveService.queue(createRecord(DecisionSeverity.CRITICAL));

            decisionArchiveService.flush();

            verify(decisionLogJpaRepository).saveAll(anyList());
            assertThat(decisionArchiveService.getPendingCount()).isZero();
        }

        @Test
        @DisplayName("flush does nothing when queue is empty")
        void flushDoesNothingWhenEmpty() {
            decisionArchiveService.flush();

            verify(decisionLogJpaRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("multiple flushes drain queue incrementally")
        void multipleFlushes() {
            decisionArchiveService.queue(createRecord(DecisionSeverity.INFO));
            decisionArchiveService.flush();

            decisionArchiveService.queue(createRecord(DecisionSeverity.WARNING));
            decisionArchiveService.flush();

            verify(decisionLogJpaRepository, times(2)).saveAll(anyList());
        }
    }

    @Nested
    @DisplayName("Persist-debug toggle")
    class PersistDebugToggle {

        @Test
        @DisplayName("DEBUG records are skipped when persist-debug is off (default)")
        void debugRecordsSkippedByDefault() {
            decisionArchiveService.queue(createRecord(DecisionSeverity.DEBUG));

            assertThat(decisionArchiveService.getPendingCount()).isZero();
        }

        @Test
        @DisplayName("DEBUG records are queued when persist-debug is on")
        void debugRecordsQueuedWhenEnabled() {
            decisionArchiveService.setPersistDebug(true);

            decisionArchiveService.queue(createRecord(DecisionSeverity.DEBUG));

            assertThat(decisionArchiveService.getPendingCount()).isOne();
        }

        @Test
        @DisplayName("INFO records are always queued regardless of toggle")
        void infoRecordsAlwaysQueued() {
            decisionArchiveService.queue(createRecord(DecisionSeverity.INFO));

            assertThat(decisionArchiveService.getPendingCount()).isOne();
        }

        @Test
        @DisplayName("toggle can be changed at runtime")
        void toggleCanBeChanged() {
            assertThat(decisionArchiveService.isPersistDebug()).isFalse();

            decisionArchiveService.setPersistDebug(true);
            assertThat(decisionArchiveService.isPersistDebug()).isTrue();

            decisionArchiveService.setPersistDebug(false);
            assertThat(decisionArchiveService.isPersistDebug()).isFalse();
        }
    }

    @Nested
    @DisplayName("Circuit breaker")
    class CircuitBreaker {

        @Test
        @DisplayName("circuit opens after 3 consecutive failures")
        void circuitOpensAfterThreeFailures() {
            doThrow(new RuntimeException("DB error"))
                    .when(decisionLogJpaRepository)
                    .saveAll(anyList());

            // Each flush will fail and re-queue the records
            for (int i = 0; i < 3; i++) {
                decisionArchiveService.queue(createRecord(DecisionSeverity.INFO));
                decisionArchiveService.flush();
            }

            assertThat(decisionArchiveService.isCircuitOpen()).isTrue();
            assertThat(decisionArchiveService.getConsecutiveFailures()).isEqualTo(3);
        }

        @Test
        @DisplayName("circuit stays closed before reaching threshold")
        void circuitStaysClosedBeforeThreshold() {
            doThrow(new RuntimeException("DB error"))
                    .when(decisionLogJpaRepository)
                    .saveAll(anyList());

            decisionArchiveService.queue(createRecord(DecisionSeverity.INFO));
            decisionArchiveService.flush();
            decisionArchiveService.queue(createRecord(DecisionSeverity.INFO));
            decisionArchiveService.flush();

            assertThat(decisionArchiveService.isCircuitOpen()).isFalse();
            assertThat(decisionArchiveService.getConsecutiveFailures()).isEqualTo(2);
        }

        @Test
        @DisplayName("flush is skipped when circuit is open")
        void flushSkippedWhenCircuitOpen() {
            doThrow(new RuntimeException("DB error"))
                    .when(decisionLogJpaRepository)
                    .saveAll(anyList());

            // Trip the circuit breaker
            for (int i = 0; i < 3; i++) {
                decisionArchiveService.queue(createRecord(DecisionSeverity.INFO));
                decisionArchiveService.flush();
            }

            assertThat(decisionArchiveService.isCircuitOpen()).isTrue();

            // Clear the mock to reset invocation count
            int pendingBefore = decisionArchiveService.getPendingCount();

            // Queue another record and try to flush
            decisionArchiveService.queue(createRecord(DecisionSeverity.INFO));
            decisionArchiveService.flush();

            // Records should remain in queue (flush was skipped due to circuit)
            assertThat(decisionArchiveService.getPendingCount()).isGreaterThan(0);
        }

        @Test
        @DisplayName("manual reset allows flushing to resume")
        void manualResetAllowsFlushing() {
            doThrow(new RuntimeException("DB error"))
                    .doThrow(new RuntimeException("DB error"))
                    .doThrow(new RuntimeException("DB error"))
                    .doReturn(List.of())
                    .when(decisionLogJpaRepository)
                    .saveAll(anyList());

            // Trip circuit
            for (int i = 0; i < 3; i++) {
                decisionArchiveService.queue(createRecord(DecisionSeverity.INFO));
                decisionArchiveService.flush();
            }

            assertThat(decisionArchiveService.isCircuitOpen()).isTrue();

            // Reset and queue new record
            decisionArchiveService.resetCircuitBreaker();
            assertThat(decisionArchiveService.isCircuitOpen()).isFalse();
            assertThat(decisionArchiveService.getConsecutiveFailures()).isZero();

            // Now flush should succeed
            decisionArchiveService.queue(createRecord(DecisionSeverity.INFO));
            decisionArchiveService.flush();

            assertThat(decisionArchiveService.isCircuitOpen()).isFalse();
        }

        @Test
        @DisplayName("successful flush resets consecutive failure count")
        void successfulFlushResetsFailures() {
            doThrow(new RuntimeException("DB error"))
                    .doReturn(List.of())
                    .when(decisionLogJpaRepository)
                    .saveAll(anyList());

            // First flush fails
            decisionArchiveService.queue(createRecord(DecisionSeverity.INFO));
            decisionArchiveService.flush();
            assertThat(decisionArchiveService.getConsecutiveFailures()).isEqualTo(1);

            // Second flush succeeds (records were re-queued from the failure)
            decisionArchiveService.flush();
            assertThat(decisionArchiveService.getConsecutiveFailures()).isZero();
        }
    }

    @Nested
    @DisplayName("Monitoring")
    class Monitoring {

        @Test
        @DisplayName("getPendingCount reflects queue size")
        void getPendingCountReflectsQueueSize() {
            assertThat(decisionArchiveService.getPendingCount()).isZero();

            decisionArchiveService.queue(createRecord(DecisionSeverity.INFO));
            decisionArchiveService.queue(createRecord(DecisionSeverity.WARNING));

            assertThat(decisionArchiveService.getPendingCount()).isEqualTo(2);

            decisionArchiveService.flush();

            assertThat(decisionArchiveService.getPendingCount()).isZero();
        }
    }
}
