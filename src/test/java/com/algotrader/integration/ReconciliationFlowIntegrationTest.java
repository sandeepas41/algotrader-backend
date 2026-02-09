package com.algotrader.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.MismatchType;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.ReconciliationResult;
import com.algotrader.event.ReconciliationEvent;
import com.algotrader.notification.Alert;
import com.algotrader.notification.NotificationService;
import com.algotrader.observability.DecisionLogger;
import com.algotrader.reconciliation.PositionReconciliationService;
import com.algotrader.reconciliation.ReconciliationAlertHandler;
import com.algotrader.repository.redis.PositionRedisRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Cross-service integration test for the reconciliation flow.
 * Wires PositionReconciliationService -> ReconciliationEvent -> ReconciliationAlertHandler
 * -> NotificationService to verify the full mismatch detection and alerting pipeline.
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationFlowIntegrationTest {

    @Mock
    private BrokerGateway brokerGateway;

    @Mock
    private PositionRedisRepository positionRedisRepository;

    @Mock
    private StrategyEngine strategyEngine;

    @Mock
    private TradingCalendarService tradingCalendarService;

    @Mock
    private DecisionLogger decisionLogger;

    @Mock
    private NotificationService notificationService;

    private PositionReconciliationService positionReconciliationService;
    private ReconciliationAlertHandler reconciliationAlertHandler;

    @BeforeEach
    void setUp() {
        // Capture the event and manually route to the handler (simulating Spring events)
        AtomicReference<ReconciliationEvent> publishedEvent = new AtomicReference<>();
        ApplicationEventPublisher eventPublisher = event -> {
            if (event instanceof ReconciliationEvent reconciliationEvent) {
                publishedEvent.set(reconciliationEvent);
            }
        };

        positionReconciliationService = new PositionReconciliationService(
                brokerGateway,
                positionRedisRepository,
                strategyEngine,
                tradingCalendarService,
                eventPublisher,
                decisionLogger);

        reconciliationAlertHandler = new ReconciliationAlertHandler(notificationService);
    }

    @Test
    @DisplayName("Full flow: broker mismatch -> reconcile -> event -> alert -> notification")
    void fullReconciliationFlow_mismatchTriggersAlert() {
        // Broker has a position that local doesn't know about
        Position brokerPos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(120.50))
                .build();

        when(brokerGateway.getPositions()).thenReturn(Map.of("net", List.of(brokerPos)));
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        // Run reconciliation
        ReconciliationResult result = positionReconciliationService.reconcile("MANUAL");

        // Verify mismatch detected
        assertThat(result.hasMismatches()).isTrue();
        assertThat(result.getMismatches().get(0).getType()).isEqualTo(MismatchType.MISSING_LOCAL);

        // Manually trigger the event handler (in production, Spring does this)
        ReconciliationEvent event = new ReconciliationEvent(this, result, true);
        reconciliationAlertHandler.onReconciliation(event);

        // Verify notification was sent
        ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(notificationService).notify(alertCaptor.capture());
        Alert alert = alertCaptor.getValue();
        assertThat(alert.getTitle()).contains("NIFTY25FEB24500CE");
    }

    @Test
    @DisplayName("Full flow: matching positions -> no alerts")
    void matchingPositions_noAlerts() {
        Position pos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(120.50))
                .build();

        when(brokerGateway.getPositions()).thenReturn(Map.of("net", List.of(pos)));
        when(positionRedisRepository.findAll()).thenReturn(List.of(pos));

        ReconciliationResult result = positionReconciliationService.reconcile("SCHEDULED");

        assertThat(result.hasMismatches()).isFalse();

        // Handler should not notify
        ReconciliationEvent event = new ReconciliationEvent(this, result, false);
        reconciliationAlertHandler.onReconciliation(event);
        verify(notificationService, never()).notify(any());
    }

    @Test
    @DisplayName("Multiple mismatches produce multiple alerts in a single run")
    void multipleMismatches_multipleAlerts() {
        // Broker has 2 positions, local has none
        Position pos1 = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(120.50))
                .build();
        Position pos2 = Position.builder()
                .instrumentToken(67890L)
                .tradingSymbol("NIFTY25FEB24500PE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(85.00))
                .build();

        when(brokerGateway.getPositions()).thenReturn(Map.of("net", List.of(pos1, pos2)));
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        ReconciliationResult result = positionReconciliationService.reconcile("STARTUP");

        assertThat(result.getTotalMismatches()).isEqualTo(2);

        ReconciliationEvent event = new ReconciliationEvent(this, result, false);
        reconciliationAlertHandler.onReconciliation(event);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(notificationService, org.mockito.Mockito.times(2)).notify(captor.capture());
    }
}
