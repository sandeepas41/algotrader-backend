package com.algotrader.reconciliation;

import com.algotrader.domain.enums.AlertSeverity;
import com.algotrader.domain.enums.AlertType;
import com.algotrader.domain.model.PositionMismatch;
import com.algotrader.domain.model.ReconciliationResult;
import com.algotrader.event.ReconciliationEvent;
import com.algotrader.notification.Alert;
import com.algotrader.notification.NotificationService;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Converts reconciliation mismatches into notification alerts.
 *
 * <p>Listens for {@link ReconciliationEvent} and routes each mismatch to the
 * notification system with severity based on mismatch type:
 * <ul>
 *   <li>MISSING_BROKER -> CRITICAL (position may have been closed externally)</li>
 *   <li>QUANTITY_MISMATCH -> WARNING (partial fill or manual trade)</li>
 *   <li>MISSING_LOCAL -> WARNING (untracked position at broker)</li>
 *   <li>PRICE_DRIFT -> INFO (cosmetic, log only)</li>
 * </ul>
 */
@Component
public class ReconciliationAlertHandler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationAlertHandler.class);

    private final NotificationService notificationService;

    public ReconciliationAlertHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Async("eventExecutor")
    @EventListener
    @org.springframework.core.annotation.Order(15)
    public void onReconciliation(ReconciliationEvent event) {
        ReconciliationResult reconciliationResult = event.getResult();

        if (!reconciliationResult.hasMismatches()) {
            return;
        }

        for (PositionMismatch mismatch : reconciliationResult.getMismatches()) {
            AlertSeverity severity =
                    switch (mismatch.getType()) {
                        case MISSING_BROKER -> AlertSeverity.CRITICAL;
                        case QUANTITY_MISMATCH -> AlertSeverity.WARNING;
                        case MISSING_LOCAL -> AlertSeverity.WARNING;
                        case PRICE_DRIFT -> AlertSeverity.INFO;
                    };

            String message = String.format(
                    "Position mismatch [%s] for %s: broker=%d, local=%d",
                    mismatch.getType(),
                    mismatch.getTradingSymbol(),
                    mismatch.getBrokerQuantity(),
                    mismatch.getLocalQuantity());

            notificationService.notify(Alert.builder()
                    .type(AlertType.RECONCILIATION)
                    .severity(severity)
                    .title("Position Mismatch: " + mismatch.getTradingSymbol())
                    .message(message)
                    .timestamp(LocalDateTime.now())
                    .build());
        }
    }
}
