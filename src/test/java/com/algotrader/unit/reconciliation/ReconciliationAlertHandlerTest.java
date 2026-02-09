package com.algotrader.unit.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.algotrader.domain.enums.AlertSeverity;
import com.algotrader.domain.enums.AlertType;
import com.algotrader.domain.enums.MismatchType;
import com.algotrader.domain.enums.ResolutionStrategy;
import com.algotrader.domain.model.PositionMismatch;
import com.algotrader.domain.model.ReconciliationResult;
import com.algotrader.event.ReconciliationEvent;
import com.algotrader.notification.Alert;
import com.algotrader.notification.NotificationService;
import com.algotrader.reconciliation.ReconciliationAlertHandler;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for ReconciliationAlertHandler verifying that mismatches are
 * routed to the notification system with correct severity levels.
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationAlertHandlerTest {

    @Mock
    private NotificationService notificationService;

    private ReconciliationAlertHandler reconciliationAlertHandler;

    @BeforeEach
    void setUp() {
        reconciliationAlertHandler = new ReconciliationAlertHandler(notificationService);
    }

    @Test
    @DisplayName("No notifications when no mismatches")
    void noMismatches_noNotifications() {
        ReconciliationResult result = ReconciliationResult.builder()
                .timestamp(LocalDateTime.now())
                .trigger("SCHEDULED")
                .build();

        ReconciliationEvent event = new ReconciliationEvent(this, result, false);
        reconciliationAlertHandler.onReconciliation(event);

        verify(notificationService, never()).notify(argThat(a -> true));
    }

    @Test
    @DisplayName("MISSING_BROKER maps to CRITICAL severity")
    void missingBroker_criticalSeverity() {
        PositionMismatch mismatch = PositionMismatch.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .type(MismatchType.MISSING_BROKER)
                .resolution(ResolutionStrategy.AUTO_SYNC)
                .brokerQuantity(0)
                .localQuantity(-50)
                .build();

        ReconciliationResult result = ReconciliationResult.builder()
                .timestamp(LocalDateTime.now())
                .trigger("MANUAL")
                .mismatches(List.of(mismatch))
                .build();

        ReconciliationEvent event = new ReconciliationEvent(this, result, true);
        reconciliationAlertHandler.onReconciliation(event);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(notificationService).notify(captor.capture());

        Alert alert = captor.getValue();
        assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(alert.getType()).isEqualTo(AlertType.RECONCILIATION);
        assertThat(alert.getTitle()).contains("NIFTY25FEB24500CE");
    }

    @Test
    @DisplayName("QUANTITY_MISMATCH maps to WARNING severity")
    void quantityMismatch_warningSeverity() {
        PositionMismatch mismatch = PositionMismatch.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .type(MismatchType.QUANTITY_MISMATCH)
                .resolution(ResolutionStrategy.AUTO_SYNC)
                .brokerQuantity(-75)
                .localQuantity(-50)
                .build();

        ReconciliationResult result = ReconciliationResult.builder()
                .timestamp(LocalDateTime.now())
                .trigger("MANUAL")
                .mismatches(List.of(mismatch))
                .build();

        ReconciliationEvent event = new ReconciliationEvent(this, result, true);
        reconciliationAlertHandler.onReconciliation(event);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(notificationService).notify(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(AlertSeverity.WARNING);
    }

    @Test
    @DisplayName("MISSING_LOCAL maps to WARNING severity")
    void missingLocal_warningSeverity() {
        PositionMismatch mismatch = PositionMismatch.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .type(MismatchType.MISSING_LOCAL)
                .resolution(ResolutionStrategy.AUTO_SYNC)
                .brokerQuantity(-50)
                .localQuantity(0)
                .build();

        ReconciliationResult result = ReconciliationResult.builder()
                .timestamp(LocalDateTime.now())
                .trigger("MANUAL")
                .mismatches(List.of(mismatch))
                .build();

        ReconciliationEvent event = new ReconciliationEvent(this, result, true);
        reconciliationAlertHandler.onReconciliation(event);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(notificationService).notify(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(AlertSeverity.WARNING);
    }

    @Test
    @DisplayName("PRICE_DRIFT maps to INFO severity")
    void priceDrift_infoSeverity() {
        PositionMismatch mismatch = PositionMismatch.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .type(MismatchType.PRICE_DRIFT)
                .resolution(ResolutionStrategy.ALERT_ONLY)
                .brokerQuantity(-50)
                .localQuantity(-50)
                .build();

        ReconciliationResult result = ReconciliationResult.builder()
                .timestamp(LocalDateTime.now())
                .trigger("MANUAL")
                .mismatches(List.of(mismatch))
                .build();

        ReconciliationEvent event = new ReconciliationEvent(this, result, true);
        reconciliationAlertHandler.onReconciliation(event);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(notificationService).notify(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(AlertSeverity.INFO);
    }

    @Test
    @DisplayName("Multiple mismatches each generate a separate alert")
    void multipleMismatches_eachGetsAlert() {
        PositionMismatch mismatch1 = PositionMismatch.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .type(MismatchType.MISSING_BROKER)
                .resolution(ResolutionStrategy.AUTO_SYNC)
                .brokerQuantity(0)
                .localQuantity(-50)
                .build();
        PositionMismatch mismatch2 = PositionMismatch.builder()
                .instrumentToken(67890L)
                .tradingSymbol("NIFTY25FEB24500PE")
                .type(MismatchType.QUANTITY_MISMATCH)
                .resolution(ResolutionStrategy.AUTO_SYNC)
                .brokerQuantity(-75)
                .localQuantity(-50)
                .build();

        ReconciliationResult result = ReconciliationResult.builder()
                .timestamp(LocalDateTime.now())
                .trigger("SCHEDULED")
                .mismatches(List.of(mismatch1, mismatch2))
                .build();

        ReconciliationEvent event = new ReconciliationEvent(this, result, false);
        reconciliationAlertHandler.onReconciliation(event);

        verify(notificationService, times(2)).notify(argThat(a -> true));
    }
}
