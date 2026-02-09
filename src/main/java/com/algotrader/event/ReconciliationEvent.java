package com.algotrader.event;

import com.algotrader.domain.model.ReconciliationResult;
import java.time.LocalDateTime;
import org.springframework.context.ApplicationEvent;

/**
 * Published after every reconciliation run (scheduled or manual) that compares
 * local positions against broker positions.
 *
 * <p>Reconciliation runs periodically (e.g., every 5 minutes during trading hours)
 * and can also be triggered manually via API or after WebSocket reconnection.
 *
 * <p>Key listeners:
 * <ul>
 *   <li>AlertService — sends alert if mismatches are found</li>
 *   <li>AuditService — logs reconciliation result</li>
 *   <li>WebSocketHandler — pushes reconciliation status to frontend</li>
 *   <li>RiskManager — may pause strategies if critical mismatches detected</li>
 * </ul>
 */
public class ReconciliationEvent extends ApplicationEvent {

    private final ReconciliationResult result;
    private final LocalDateTime reconciledAt;
    private final boolean manual;

    /**
     * @param source  the component publishing this event
     * @param result  the reconciliation result with match status and mismatches
     * @param manual  true if triggered via API, false if scheduled
     */
    public ReconciliationEvent(Object source, ReconciliationResult result, boolean manual) {
        super(source);
        this.result = result;
        this.reconciledAt = LocalDateTime.now();
        this.manual = manual;
    }

    public ReconciliationResult getResult() {
        return result;
    }

    public LocalDateTime getReconciledAt() {
        return reconciledAt;
    }

    /** True if triggered by manual API call, false if triggered by scheduled job. */
    public boolean isManual() {
        return manual;
    }
}
