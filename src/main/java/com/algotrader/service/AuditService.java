package com.algotrader.service;

import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.entity.AuditLogEntity;
import com.algotrader.mapper.JsonHelper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Audit logging service that enriches audit entries with market context.
 *
 * <p>Every state change in the system (order placed, strategy updated, position closed, etc.)
 * should be audited via this service. The audit entry includes a {@code contextJson} field
 * that captures the market snapshot at decision time -- spot price, IV, delta, margin
 * utilization, trigger reason, and market phase -- enabling post-hoc analysis of
 * why a particular action was taken.
 *
 * <p>Audit entries are not written directly to H2. Instead, they are queued in
 * {@link DataSyncService}'s write-behind buffer for batch persistence, keeping
 * the trading hot path free from synchronous DB writes.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final DataSyncService dataSyncService;
    private final TradingCalendarService tradingCalendarService;

    public AuditService(DataSyncService dataSyncService, TradingCalendarService tradingCalendarService) {
        this.dataSyncService = dataSyncService;
        this.tradingCalendarService = tradingCalendarService;
    }

    /**
     * Logs an audit entry without market context.
     * Use this for non-trading events (user login, config change, etc.).
     */
    public void log(String eventType, String entityType, String entityId, String action) {
        log(eventType, entityType, entityId, action, null, null, null);
    }

    /**
     * Logs an audit entry with old/new values for change tracking.
     */
    public void log(
            String eventType, String entityType, String entityId, String action, String oldValue, String newValue) {
        log(eventType, entityType, entityId, action, oldValue, newValue, null);
    }

    /**
     * Logs a fully specified audit entry with old/new values and market context.
     */
    public void log(
            String eventType,
            String entityType,
            String entityId,
            String action,
            String oldValue,
            String newValue,
            Map<String, Object> marketContext) {

        AuditLogEntity auditLogEntity = AuditLogEntity.builder()
                .eventType(eventType)
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .oldValue(oldValue)
                .newValue(newValue)
                .contextJson(buildContextJson(marketContext))
                .timestamp(LocalDateTime.now())
                .build();

        dataSyncService.queueAuditLog(auditLogEntity);
    }

    /**
     * Logs a trading decision with automatic market phase enrichment.
     * The market phase is always included in the context; callers can add
     * additional fields (spotPrice, atmIV, positionDelta, triggerReason, etc.).
     */
    public void logTradingDecision(
            String eventType, String entityType, String entityId, String action, Map<String, Object> tradingContext) {

        Map<String, Object> enrichedContext = enrichWithMarketPhase(tradingContext);

        AuditLogEntity auditLogEntity = AuditLogEntity.builder()
                .eventType(eventType)
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .contextJson(buildContextJson(enrichedContext))
                .timestamp(LocalDateTime.now())
                .build();

        dataSyncService.queueAuditLog(auditLogEntity);
    }

    /**
     * Enriches a trading context map with the current market phase and timestamp.
     */
    private Map<String, Object> enrichWithMarketPhase(Map<String, Object> context) {
        Map<String, Object> enriched = new LinkedHashMap<>();
        if (context != null) {
            enriched.putAll(context);
        }
        enriched.put("marketPhase", tradingCalendarService.getCurrentPhase().name());
        enriched.put("timestamp", LocalDateTime.now().toString());
        return enriched;
    }

    /**
     * Serializes a map to JSON string for the context_json column.
     * Uses the project's shared {@link JsonHelper} utility.
     */
    private String buildContextJson(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        try {
            return JsonHelper.toJson(context);
        } catch (Exception e) {
            log.warn("Failed to serialize audit context to JSON: {}", e.getMessage());
            return null;
        }
    }
}
