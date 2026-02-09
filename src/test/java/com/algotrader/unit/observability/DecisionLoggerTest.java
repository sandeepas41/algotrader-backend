package com.algotrader.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.algotrader.domain.enums.DecisionOutcome;
import com.algotrader.domain.enums.DecisionSeverity;
import com.algotrader.domain.enums.DecisionSource;
import com.algotrader.domain.enums.DecisionType;
import com.algotrader.domain.model.DecisionRecord;
import com.algotrader.event.DecisionLogEvent;
import com.algotrader.observability.DecisionArchiveService;
import com.algotrader.observability.DecisionLogger;
import com.algotrader.risk.RiskViolation;
import com.algotrader.strategy.base.MarketSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for the DecisionLogger service.
 *
 * <p>Verifies ring buffer management, specialized log methods, event publishing,
 * and integration with DecisionArchiveService.
 */
class DecisionLoggerTest {

    private ApplicationEventPublisher applicationEventPublisher;
    private DecisionArchiveService decisionArchiveService;
    private DecisionLogger decisionLogger;

    @BeforeEach
    void setUp() {
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        decisionArchiveService = mock(DecisionArchiveService.class);
        decisionLogger = new DecisionLogger(applicationEventPublisher, decisionArchiveService);
    }

    @Nested
    @DisplayName("Core logging")
    class CoreLogging {

        @Test
        @DisplayName("log() creates record with all fields set")
        void logCreatesRecordWithAllFields() {
            Map<String, Object> context = Map.of("key", "value");

            DecisionRecord record = decisionLogger.log(
                    DecisionSource.STRATEGY_ENGINE,
                    "STR-001",
                    DecisionType.STRATEGY_ENTRY_TRIGGERED,
                    DecisionOutcome.TRIGGERED,
                    "IV above threshold",
                    context,
                    DecisionSeverity.INFO);

            assertThat(record.getSource()).isEqualTo(DecisionSource.STRATEGY_ENGINE);
            assertThat(record.getSourceId()).isEqualTo("STR-001");
            assertThat(record.getDecisionType()).isEqualTo(DecisionType.STRATEGY_ENTRY_TRIGGERED);
            assertThat(record.getOutcome()).isEqualTo(DecisionOutcome.TRIGGERED);
            assertThat(record.getReasoning()).isEqualTo("IV above threshold");
            assertThat(record.getDataContext()).containsEntry("key", "value");
            assertThat(record.getSeverity()).isEqualTo(DecisionSeverity.INFO);
            assertThat(record.getTimestamp()).isNotNull();
            assertThat(record.getSessionDate()).isNotNull();
        }

        @Test
        @DisplayName("log() publishes DecisionLogEvent")
        void logPublishesEvent() {
            decisionLogger.log(
                    DecisionSource.SYSTEM,
                    null,
                    DecisionType.SESSION_EXPIRED,
                    DecisionOutcome.INFO,
                    "Session expired",
                    null,
                    DecisionSeverity.WARNING);

            ArgumentCaptor<DecisionLogEvent> captor = ArgumentCaptor.forClass(DecisionLogEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());

            DecisionRecord captured = captor.getValue().getDecisionRecord();
            assertThat(captured.getDecisionType()).isEqualTo(DecisionType.SESSION_EXPIRED);
        }

        @Test
        @DisplayName("log() queues record for archive")
        void logQueuesForArchive() {
            decisionLogger.log(
                    DecisionSource.RISK_MANAGER,
                    "ORD-123",
                    DecisionType.RISK_ORDER_REJECTED,
                    DecisionOutcome.REJECTED,
                    "Limit exceeded",
                    null,
                    DecisionSeverity.WARNING);

            ArgumentCaptor<DecisionRecord> captor = ArgumentCaptor.forClass(DecisionRecord.class);
            verify(decisionArchiveService).queue(captor.capture());

            assertThat(captor.getValue().getSourceId()).isEqualTo("ORD-123");
        }

        @Test
        @DisplayName("log() survives event publishing failure")
        void logSurvivesEventPublishingFailure() {
            doThrow(new RuntimeException("Event bus error"))
                    .when(applicationEventPublisher)
                    .publishEvent(any());

            // Should not throw
            DecisionRecord record = decisionLogger.log(
                    DecisionSource.SYSTEM,
                    null,
                    DecisionType.STALE_DATA_DETECTED,
                    DecisionOutcome.INFO,
                    "Stale tick data",
                    null,
                    DecisionSeverity.WARNING);

            assertThat(record).isNotNull();
            // Record should still be in ring buffer
            assertThat(decisionLogger.getRecentDecisions(1)).hasSize(1);
            // Should still be queued for archive
            verify(decisionArchiveService).queue(any());
        }
    }

    @Nested
    @DisplayName("Ring buffer")
    class RingBuffer {

        @Test
        @DisplayName("ring buffer maintains max size of 1000")
        void ringBufferEvictsOldest() {
            // Fill beyond capacity
            for (int i = 0; i < 1050; i++) {
                decisionLogger.log(
                        DecisionSource.STRATEGY_ENGINE,
                        "STR-" + i,
                        DecisionType.STRATEGY_ENTRY_EVALUATED,
                        DecisionOutcome.SKIPPED,
                        "Evaluation #" + i,
                        null,
                        DecisionSeverity.DEBUG);
            }

            assertThat(decisionLogger.getBufferSize()).isEqualTo(1000);

            // Newest should be first
            List<DecisionRecord> recent = decisionLogger.getRecentDecisions(1);
            assertThat(recent.get(0).getSourceId()).isEqualTo("STR-1049");
        }

        @Test
        @DisplayName("getRecentDecisions returns newest first")
        void getRecentDecisionsNewestFirst() {
            decisionLogger.log(
                    DecisionSource.STRATEGY_ENGINE,
                    "STR-A",
                    DecisionType.STRATEGY_DEPLOYED,
                    DecisionOutcome.INFO,
                    "First",
                    null,
                    DecisionSeverity.INFO);
            decisionLogger.log(
                    DecisionSource.STRATEGY_ENGINE,
                    "STR-B",
                    DecisionType.STRATEGY_ARMED,
                    DecisionOutcome.INFO,
                    "Second",
                    null,
                    DecisionSeverity.INFO);
            decisionLogger.log(
                    DecisionSource.STRATEGY_ENGINE,
                    "STR-C",
                    DecisionType.STRATEGY_CLOSED,
                    DecisionOutcome.INFO,
                    "Third",
                    null,
                    DecisionSeverity.INFO);

            List<DecisionRecord> recent = decisionLogger.getRecentDecisions(3);
            assertThat(recent).hasSize(3);
            assertThat(recent.get(0).getSourceId()).isEqualTo("STR-C");
            assertThat(recent.get(1).getSourceId()).isEqualTo("STR-B");
            assertThat(recent.get(2).getSourceId()).isEqualTo("STR-A");
        }

        @Test
        @DisplayName("getRecentDecisions limits count")
        void getRecentDecisionsLimitsCount() {
            for (int i = 0; i < 10; i++) {
                decisionLogger.log(
                        DecisionSource.SYSTEM,
                        null,
                        DecisionType.STALE_DATA_DETECTED,
                        DecisionOutcome.INFO,
                        "Event " + i,
                        null,
                        DecisionSeverity.DEBUG);
            }

            assertThat(decisionLogger.getRecentDecisions(3)).hasSize(3);
        }

        @Test
        @DisplayName("getRecentDecisions filters by source")
        void getRecentDecisionsFiltersBySource() {
            decisionLogger.log(
                    DecisionSource.STRATEGY_ENGINE,
                    "STR-1",
                    DecisionType.STRATEGY_ENTRY_TRIGGERED,
                    DecisionOutcome.TRIGGERED,
                    "Entry",
                    null,
                    DecisionSeverity.INFO);
            decisionLogger.log(
                    DecisionSource.RISK_MANAGER,
                    "ORD-1",
                    DecisionType.RISK_ORDER_VALIDATED,
                    DecisionOutcome.TRIGGERED,
                    "Validated",
                    null,
                    DecisionSeverity.DEBUG);
            decisionLogger.log(
                    DecisionSource.STRATEGY_ENGINE,
                    "STR-2",
                    DecisionType.STRATEGY_EXIT_TRIGGERED,
                    DecisionOutcome.TRIGGERED,
                    "Exit",
                    null,
                    DecisionSeverity.INFO);

            List<DecisionRecord> strategyOnly = decisionLogger.getRecentDecisions(10, DecisionSource.STRATEGY_ENGINE);
            assertThat(strategyOnly).hasSize(2);
            assertThat(strategyOnly).allMatch(r -> r.getSource() == DecisionSource.STRATEGY_ENGINE);
        }

        @Test
        @DisplayName("getRecentDecisions filters by minimum severity")
        void getRecentDecisionsFiltersBySeverity() {
            decisionLogger.log(
                    DecisionSource.STRATEGY_ENGINE,
                    "STR-1",
                    DecisionType.STRATEGY_ENTRY_EVALUATED,
                    DecisionOutcome.SKIPPED,
                    "Debug",
                    null,
                    DecisionSeverity.DEBUG);
            decisionLogger.log(
                    DecisionSource.STRATEGY_ENGINE,
                    "STR-2",
                    DecisionType.STRATEGY_ENTRY_TRIGGERED,
                    DecisionOutcome.TRIGGERED,
                    "Info",
                    null,
                    DecisionSeverity.INFO);
            decisionLogger.log(
                    DecisionSource.RISK_MANAGER,
                    "ORD-1",
                    DecisionType.RISK_LIMIT_BREACH,
                    DecisionOutcome.TRIGGERED,
                    "Warning",
                    null,
                    DecisionSeverity.WARNING);
            decisionLogger.log(
                    DecisionSource.KILL_SWITCH,
                    null,
                    DecisionType.KILL_SWITCH_ACTIVATED,
                    DecisionOutcome.TRIGGERED,
                    "Critical",
                    null,
                    DecisionSeverity.CRITICAL);

            List<DecisionRecord> warningAndAbove = decisionLogger.getRecentDecisions(10, DecisionSeverity.WARNING);
            assertThat(warningAndAbove).hasSize(2);
            assertThat(warningAndAbove).allMatch(r -> r.getSeverity().ordinal() >= DecisionSeverity.WARNING.ordinal());
        }
    }

    @Nested
    @DisplayName("Specialized log methods")
    class SpecializedMethods {

        @Test
        @DisplayName("logStrategyEvaluation sets correct fields for triggered entry")
        void logStrategyEvaluationTriggered() {
            MarketSnapshot marketSnapshot = MarketSnapshot.builder()
                    .spotPrice(new BigDecimal("22500"))
                    .atmIV(new BigDecimal("15.2"))
                    .timestamp(LocalDateTime.now())
                    .build();

            decisionLogger.logStrategyEvaluation(
                    "STR-001",
                    DecisionType.STRATEGY_ENTRY_TRIGGERED,
                    true,
                    "ATM IV 15.2 > threshold 14.0",
                    marketSnapshot);

            List<DecisionRecord> recent = decisionLogger.getRecentDecisions(1);
            DecisionRecord record = recent.get(0);

            assertThat(record.getSource()).isEqualTo(DecisionSource.STRATEGY_ENGINE);
            assertThat(record.getSourceId()).isEqualTo("STR-001");
            assertThat(record.getOutcome()).isEqualTo(DecisionOutcome.TRIGGERED);
            assertThat(record.getSeverity()).isEqualTo(DecisionSeverity.INFO);
            assertThat(record.getDataContext()).containsKey("spotPrice");
            assertThat(record.getDataContext()).containsKey("atmIV");
        }

        @Test
        @DisplayName("logStrategyEvaluation sets DEBUG for skipped evaluation")
        void logStrategyEvaluationSkipped() {
            decisionLogger.logStrategyEvaluation(
                    "STR-002", DecisionType.STRATEGY_ENTRY_EVALUATED, false, "IV below threshold", null);

            DecisionRecord record = decisionLogger.getRecentDecisions(1).get(0);
            assertThat(record.getOutcome()).isEqualTo(DecisionOutcome.SKIPPED);
            assertThat(record.getSeverity()).isEqualTo(DecisionSeverity.DEBUG);
        }

        @Test
        @DisplayName("logRiskDecision captures violations for rejected order")
        void logRiskDecisionRejected() {
            List<RiskViolation> violations = List.of(
                    RiskViolation.of("DAILY_LOSS_LIMIT", "Daily loss -50000 exceeds limit -40000"),
                    RiskViolation.of("POSITION_SIZE", "Max 10 lots exceeded"));

            decisionLogger.logRiskDecision("ORD-456", false, violations, "Order rejected by risk manager");

            DecisionRecord record = decisionLogger.getRecentDecisions(1).get(0);
            assertThat(record.getSource()).isEqualTo(DecisionSource.RISK_MANAGER);
            assertThat(record.getDecisionType()).isEqualTo(DecisionType.RISK_ORDER_REJECTED);
            assertThat(record.getOutcome()).isEqualTo(DecisionOutcome.REJECTED);
            assertThat(record.getSeverity()).isEqualTo(DecisionSeverity.WARNING);
            @SuppressWarnings("unchecked")
            List<String> capturedViolations =
                    (List<String>) record.getDataContext().get("violations");
            assertThat(capturedViolations).hasSize(2);
        }

        @Test
        @DisplayName("logRiskDecision sets DEBUG for approved order")
        void logRiskDecisionApproved() {
            decisionLogger.logRiskDecision("ORD-789", true, null, "All risk checks passed");

            DecisionRecord record = decisionLogger.getRecentDecisions(1).get(0);
            assertThat(record.getDecisionType()).isEqualTo(DecisionType.RISK_ORDER_VALIDATED);
            assertThat(record.getOutcome()).isEqualTo(DecisionOutcome.TRIGGERED);
            assertThat(record.getSeverity()).isEqualTo(DecisionSeverity.DEBUG);
        }

        @Test
        @DisplayName("logAdjustmentEvaluation with triggered adjustment")
        void logAdjustmentEvaluationTriggered() {
            Map<String, Object> indicators = new LinkedHashMap<>();
            indicators.put("delta", -0.35);
            indicators.put("threshold", -0.30);

            decisionLogger.logAdjustmentEvaluation("STR-001", true, "Delta -0.35 crossed threshold -0.30", indicators);

            DecisionRecord record = decisionLogger.getRecentDecisions(1).get(0);
            assertThat(record.getSource()).isEqualTo(DecisionSource.ADJUSTMENT);
            assertThat(record.getDecisionType()).isEqualTo(DecisionType.ADJUSTMENT_TRIGGERED);
            assertThat(record.getOutcome()).isEqualTo(DecisionOutcome.TRIGGERED);
            assertThat(record.getSeverity()).isEqualTo(DecisionSeverity.INFO);
        }

        @Test
        @DisplayName("logKillSwitch sets CRITICAL severity")
        void logKillSwitch() {
            Map<String, Object> details = Map.of("triggeredBy", "user", "activeStrategies", 3);

            decisionLogger.logKillSwitch("Manual kill switch activated", details);

            DecisionRecord record = decisionLogger.getRecentDecisions(1).get(0);
            assertThat(record.getSource()).isEqualTo(DecisionSource.KILL_SWITCH);
            assertThat(record.getDecisionType()).isEqualTo(DecisionType.KILL_SWITCH_ACTIVATED);
            assertThat(record.getSeverity()).isEqualTo(DecisionSeverity.CRITICAL);
            assertThat(record.getDataContext()).containsEntry("triggeredBy", "user");
        }

        @Test
        @DisplayName("logSystemEvent with stale data detection")
        void logSystemEvent() {
            decisionLogger.logSystemEvent(
                    DecisionType.STALE_DATA_DETECTED,
                    "No ticks received for 30s",
                    Map.of("instrumentToken", 256265L),
                    DecisionSeverity.WARNING);

            DecisionRecord record = decisionLogger.getRecentDecisions(1).get(0);
            assertThat(record.getSource()).isEqualTo(DecisionSource.SYSTEM);
            assertThat(record.getDecisionType()).isEqualTo(DecisionType.STALE_DATA_DETECTED);
            assertThat(record.getOutcome()).isEqualTo(DecisionOutcome.INFO);
        }

        @Test
        @DisplayName("logOrderEvent with rejected order sets WARNING")
        void logOrderEventRejected() {
            decisionLogger.logOrderEvent(
                    "ORD-100",
                    DecisionType.ORDER_REJECTED,
                    DecisionOutcome.REJECTED,
                    "Insufficient margin",
                    Map.of("requiredMargin", 50000, "availableMargin", 30000));

            DecisionRecord record = decisionLogger.getRecentDecisions(1).get(0);
            assertThat(record.getSource()).isEqualTo(DecisionSource.ORDER_ROUTER);
            assertThat(record.getSeverity()).isEqualTo(DecisionSeverity.WARNING);
        }

        @Test
        @DisplayName("logOrderEvent with placed order sets INFO")
        void logOrderEventPlaced() {
            decisionLogger.logOrderEvent(
                    "ORD-101",
                    DecisionType.ORDER_PLACED,
                    DecisionOutcome.TRIGGERED,
                    "Market order placed",
                    Map.of("price", 250.5, "qty", 50));

            DecisionRecord record = decisionLogger.getRecentDecisions(1).get(0);
            assertThat(record.getSeverity()).isEqualTo(DecisionSeverity.INFO);
        }

        @Test
        @DisplayName("logStrategyLifecycle logs deployment")
        void logStrategyLifecycle() {
            decisionLogger.logStrategyLifecycle(
                    "STR-005", DecisionType.STRATEGY_DEPLOYED, "Iron Condor deployed on NIFTY");

            DecisionRecord record = decisionLogger.getRecentDecisions(1).get(0);
            assertThat(record.getSource()).isEqualTo(DecisionSource.STRATEGY_ENGINE);
            assertThat(record.getDecisionType()).isEqualTo(DecisionType.STRATEGY_DEPLOYED);
            assertThat(record.getOutcome()).isEqualTo(DecisionOutcome.INFO);
        }

        @Test
        @DisplayName("logMorph logs successful morph")
        void logMorphSuccess() {
            decisionLogger.logMorph(
                    "STR-010",
                    true,
                    "Straddle morphed to strangle",
                    Map.of("targetType", "STRANGLE", "legsClosedCount", 2, "legsOpenedCount", 2));

            DecisionRecord record = decisionLogger.getRecentDecisions(1).get(0);
            assertThat(record.getSource()).isEqualTo(DecisionSource.MORPH_SERVICE);
            assertThat(record.getDecisionType()).isEqualTo(DecisionType.MORPH_EXECUTED);
            assertThat(record.getOutcome()).isEqualTo(DecisionOutcome.TRIGGERED);
        }

        @Test
        @DisplayName("logMorph logs failed morph")
        void logMorphFailure() {
            decisionLogger.logMorph("STR-010", false, "Morph failed: insufficient margin", null);

            DecisionRecord record = decisionLogger.getRecentDecisions(1).get(0);
            assertThat(record.getDecisionType()).isEqualTo(DecisionType.MORPH_FAILED);
            assertThat(record.getOutcome()).isEqualTo(DecisionOutcome.FAILED);
        }

        @Test
        @DisplayName("logRiskBreach captures breach details")
        void logRiskBreach() {
            decisionLogger.logRiskBreach(
                    "ACCOUNT", "Margin utilization at 92%", Map.of("utilization", 92.0, "threshold", 90.0));

            DecisionRecord record = decisionLogger.getRecentDecisions(1).get(0);
            assertThat(record.getSource()).isEqualTo(DecisionSource.RISK_MANAGER);
            assertThat(record.getDecisionType()).isEqualTo(DecisionType.RISK_LIMIT_BREACH);
            assertThat(record.getSeverity()).isEqualTo(DecisionSeverity.WARNING);
        }
    }

    @Nested
    @DisplayName("Event publishing")
    class EventPublishing {

        @Test
        @DisplayName("each log call publishes exactly one DecisionLogEvent")
        void eachLogPublishesOneEvent() {
            decisionLogger.log(
                    DecisionSource.STRATEGY_ENGINE,
                    "STR-1",
                    DecisionType.STRATEGY_ENTRY_TRIGGERED,
                    DecisionOutcome.TRIGGERED,
                    "Entry",
                    null,
                    DecisionSeverity.INFO);
            decisionLogger.log(
                    DecisionSource.RISK_MANAGER,
                    "ORD-1",
                    DecisionType.RISK_ORDER_VALIDATED,
                    DecisionOutcome.TRIGGERED,
                    "OK",
                    null,
                    DecisionSeverity.DEBUG);

            verify(applicationEventPublisher, times(2)).publishEvent(any(DecisionLogEvent.class));
        }

        @Test
        @DisplayName("each log call queues exactly one record for archive")
        void eachLogQueuesForArchive() {
            decisionLogger.log(
                    DecisionSource.SYSTEM,
                    null,
                    DecisionType.SESSION_EXPIRED,
                    DecisionOutcome.INFO,
                    "Expired",
                    null,
                    DecisionSeverity.WARNING);

            verify(decisionArchiveService, times(1)).queue(any(DecisionRecord.class));
        }
    }
}
