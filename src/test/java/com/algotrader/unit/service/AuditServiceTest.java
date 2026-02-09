package com.algotrader.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.domain.enums.MarketPhase;
import com.algotrader.entity.AuditLogEntity;
import com.algotrader.service.AuditService;
import com.algotrader.service.DataSyncService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for AuditService covering audit log creation,
 * market context enrichment, and DataSyncService delegation.
 */
class AuditServiceTest {

    private DataSyncService dataSyncService;
    private TradingCalendarService tradingCalendarService;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        dataSyncService = mock(DataSyncService.class);
        tradingCalendarService = mock(TradingCalendarService.class);
        when(tradingCalendarService.getCurrentPhase()).thenReturn(MarketPhase.NORMAL);

        auditService = new AuditService(dataSyncService, tradingCalendarService);
    }

    @Nested
    @DisplayName("Basic Audit Logging")
    class BasicAuditLogging {

        @Test
        @DisplayName("log queues audit entry with correct fields")
        void logQueuesAuditEntry() {
            auditService.log("ORDER_PLACED", "Order", "O123", "PLACE");

            ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
            verify(dataSyncService).queueAuditLog(captor.capture());

            AuditLogEntity entry = captor.getValue();
            assertThat(entry.getEventType()).isEqualTo("ORDER_PLACED");
            assertThat(entry.getEntityType()).isEqualTo("Order");
            assertThat(entry.getEntityId()).isEqualTo("O123");
            assertThat(entry.getAction()).isEqualTo("PLACE");
            assertThat(entry.getTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("log without context has null contextJson")
        void logWithoutContextHasNullContextJson() {
            auditService.log("LOGIN", "User", "U1", "LOGIN");

            ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
            verify(dataSyncService).queueAuditLog(captor.capture());

            assertThat(captor.getValue().getContextJson()).isNull();
        }

        @Test
        @DisplayName("log with old/new values preserves them")
        void logWithOldNewValues() {
            auditService.log(
                    "STRATEGY_UPDATED",
                    "Strategy",
                    "S1",
                    "UPDATE",
                    "{\"status\":\"ACTIVE\"}",
                    "{\"status\":\"PAUSED\"}");

            ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
            verify(dataSyncService).queueAuditLog(captor.capture());

            AuditLogEntity entry = captor.getValue();
            assertThat(entry.getOldValue()).isEqualTo("{\"status\":\"ACTIVE\"}");
            assertThat(entry.getNewValue()).isEqualTo("{\"status\":\"PAUSED\"}");
        }
    }

    @Nested
    @DisplayName("Market Context Enrichment")
    class MarketContextEnrichment {

        @Test
        @DisplayName("logTradingDecision enriches context with market phase")
        void logTradingDecisionEnrichesWithMarketPhase() {
            Map<String, Object> context = Map.of("spotPrice", 22150.50, "atmIV", 16.5);

            auditService.logTradingDecision("ADJUSTMENT_TRIGGERED", "Strategy", "S1", "DELTA_SHIFT", context);

            ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
            verify(dataSyncService).queueAuditLog(captor.capture());

            String contextJson = captor.getValue().getContextJson();
            assertThat(contextJson).isNotNull();
            assertThat(contextJson).contains("\"marketPhase\":\"NORMAL\"");
            assertThat(contextJson).contains("\"spotPrice\"");
            assertThat(contextJson).contains("\"timestamp\"");
        }

        @Test
        @DisplayName("logTradingDecision with null context still includes market phase")
        void logTradingDecisionNullContextStillHasPhase() {
            auditService.logTradingDecision("ORDER_REJECTED", "Order", "O1", "REJECT", null);

            ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
            verify(dataSyncService).queueAuditLog(captor.capture());

            String contextJson = captor.getValue().getContextJson();
            assertThat(contextJson).contains("\"marketPhase\":\"NORMAL\"");
        }

        @Test
        @DisplayName("logTradingDecision reflects current market phase")
        void logTradingDecisionReflectsCurrentPhase() {
            when(tradingCalendarService.getCurrentPhase()).thenReturn(MarketPhase.PRE_OPEN);

            auditService.logTradingDecision(
                    "ENTRY_BLOCKED", "Strategy", "S1", "BLOCK", Map.of("reason", "Pre-open phase"));

            ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
            verify(dataSyncService).queueAuditLog(captor.capture());

            assertThat(captor.getValue().getContextJson()).contains("\"marketPhase\":\"PRE_OPEN\"");
        }
    }

    @Nested
    @DisplayName("Context with Old/New Values")
    class ContextWithOldNewValues {

        @Test
        @DisplayName("log with all fields populated")
        void logWithAllFieldsPopulated() {
            Map<String, Object> context = Map.of("triggerReason", "Delta threshold exceeded");

            auditService.log(
                    "ADJUSTMENT_EXECUTED",
                    "Strategy",
                    "S1",
                    "ADJUST",
                    "{\"delta\":-0.25}",
                    "{\"delta\":-0.15}",
                    context);

            ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
            verify(dataSyncService).queueAuditLog(captor.capture());

            AuditLogEntity entry = captor.getValue();
            assertThat(entry.getEventType()).isEqualTo("ADJUSTMENT_EXECUTED");
            assertThat(entry.getOldValue()).isEqualTo("{\"delta\":-0.25}");
            assertThat(entry.getNewValue()).isEqualTo("{\"delta\":-0.15}");
            assertThat(entry.getContextJson()).contains("triggerReason");
        }
    }

    @Nested
    @DisplayName("Delegation to DataSyncService")
    class Delegation {

        @Test
        @DisplayName("All log methods delegate to DataSyncService.queueAuditLog")
        void allMethodsDelegateToDataSync() {
            auditService.log("E1", "T1", "ID1", "A1");
            auditService.log("E2", "T2", "ID2", "A2", "old", "new");
            auditService.logTradingDecision("E3", "T3", "ID3", "A3", Map.of("key", "value"));

            verify(dataSyncService, org.mockito.Mockito.times(3)).queueAuditLog(any(AuditLogEntity.class));
        }
    }
}
