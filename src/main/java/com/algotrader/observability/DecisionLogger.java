package com.algotrader.observability;

import com.algotrader.domain.enums.DecisionOutcome;
import com.algotrader.domain.enums.DecisionSeverity;
import com.algotrader.domain.enums.DecisionSource;
import com.algotrader.domain.enums.DecisionType;
import com.algotrader.domain.model.DecisionRecord;
import com.algotrader.event.DecisionLogEvent;
import com.algotrader.risk.RiskViolation;
import com.algotrader.strategy.base.MarketSnapshot;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Central decision logging service for the AlgoTrader platform.
 *
 * <p>Every automated trading decision -- strategy evaluation, risk check, adjustment,
 * morph, kill switch activation -- is captured as a structured {@link DecisionRecord}
 * with full reasoning and market context. This enables:
 * <ul>
 *   <li>Real-time monitoring via WebSocket (DecisionLogEvent -> WebSocket handler)</li>
 *   <li>Post-session analysis via H2 persistence (async batch via DecisionArchiveService)</li>
 *   <li>Fast in-memory queries via ring buffer (last 1000 decisions)</li>
 * </ul>
 *
 * <p>Specialized methods (logStrategyEvaluation, logRiskDecision, etc.) provide
 * convenient entry points for each subsystem, automatically setting the correct
 * source, decision type, outcome, and severity.
 *
 * <p>The ring buffer uses a {@link ConcurrentLinkedDeque} with a max size of
 * {@value #RING_BUFFER_SIZE}. New entries are added at the front (newest first),
 * and the oldest entries are evicted when the buffer is full.
 *
 * <p>The generic {@link #log(DecisionSource, String, DecisionType, DecisionOutcome,
 * String, Map, DecisionSeverity)} method is the core entry point that all specialized
 * methods delegate to.
 */
@Service
public class DecisionLogger {

    private static final Logger logger = LoggerFactory.getLogger(DecisionLogger.class);

    static final int RING_BUFFER_SIZE = 1000;

    private final ApplicationEventPublisher applicationEventPublisher;
    private final DecisionArchiveService decisionArchiveService;

    private final ConcurrentLinkedDeque<DecisionRecord> ringBuffer = new ConcurrentLinkedDeque<>();

    public DecisionLogger(
            ApplicationEventPublisher applicationEventPublisher, DecisionArchiveService decisionArchiveService) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.decisionArchiveService = decisionArchiveService;
    }

    // ---- Core logging method ----

    /**
     * Creates a DecisionRecord, adds it to the ring buffer, publishes a DecisionLogEvent,
     * and queues it for async H2 persistence.
     */
    public DecisionRecord log(
            DecisionSource source,
            String sourceId,
            DecisionType decisionType,
            DecisionOutcome outcome,
            String reasoning,
            Map<String, Object> dataContext,
            DecisionSeverity severity) {

        DecisionRecord decisionRecord = DecisionRecord.builder()
                .timestamp(LocalDateTime.now())
                .source(source)
                .sourceId(sourceId)
                .decisionType(decisionType)
                .outcome(outcome)
                .reasoning(reasoning)
                .dataContext(dataContext)
                .severity(severity)
                .sessionDate(LocalDate.now())
                .replaySessionId(replayMode ? replaySessionId : null)
                .build();

        persist(decisionRecord);

        return decisionRecord;
    }

    // ---- Strategy evaluation ----

    /**
     * Logs a strategy entry/exit evaluation with market context.
     *
     * @param strategyId the strategy being evaluated
     * @param decisionType the specific evaluation type (STRATEGY_ENTRY_EVALUATED, etc.)
     * @param triggered whether the condition was met and action was taken
     * @param reasoning human-readable explanation
     * @param marketSnapshot market conditions at evaluation time
     */
    public void logStrategyEvaluation(
            String strategyId,
            DecisionType decisionType,
            boolean triggered,
            String reasoning,
            MarketSnapshot marketSnapshot) {

        Map<String, Object> context = new LinkedHashMap<>();
        if (marketSnapshot != null) {
            context.put("spotPrice", marketSnapshot.getSpotPrice());
            context.put("atmIV", marketSnapshot.getAtmIV());
            context.put("timestamp", marketSnapshot.getTimestamp());
        }

        log(
                DecisionSource.STRATEGY_ENGINE,
                strategyId,
                decisionType,
                triggered ? DecisionOutcome.TRIGGERED : DecisionOutcome.SKIPPED,
                reasoning,
                context,
                triggered ? DecisionSeverity.INFO : DecisionSeverity.DEBUG);
    }

    // ---- Risk decision ----

    /**
     * Logs a risk validation decision (order approved or rejected).
     *
     * @param orderId the order being validated
     * @param approved whether the order passed risk checks
     * @param violations list of violations if rejected (can be null)
     * @param reasoning human-readable explanation
     */
    public void logRiskDecision(String orderId, boolean approved, List<RiskViolation> violations, String reasoning) {

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("orderId", orderId);
        context.put("approved", approved);
        if (violations != null && !violations.isEmpty()) {
            context.put(
                    "violations",
                    violations.stream().map(RiskViolation::toString).toList());
        }

        log(
                DecisionSource.RISK_MANAGER,
                orderId,
                approved ? DecisionType.RISK_ORDER_VALIDATED : DecisionType.RISK_ORDER_REJECTED,
                approved ? DecisionOutcome.TRIGGERED : DecisionOutcome.REJECTED,
                reasoning,
                context,
                approved ? DecisionSeverity.DEBUG : DecisionSeverity.WARNING);
    }

    // ---- Adjustment evaluation ----

    /**
     * Logs an adjustment evaluation for a strategy.
     *
     * @param strategyId the strategy whose adjustment was evaluated
     * @param triggered whether the adjustment was triggered
     * @param reasoning human-readable explanation
     * @param indicatorValues relevant indicator values at evaluation time
     */
    public void logAdjustmentEvaluation(
            String strategyId, boolean triggered, String reasoning, Map<String, Object> indicatorValues) {

        Map<String, Object> context = new LinkedHashMap<>();
        if (indicatorValues != null) {
            context.put("indicators", indicatorValues);
        }

        log(
                DecisionSource.ADJUSTMENT,
                strategyId,
                triggered ? DecisionType.ADJUSTMENT_TRIGGERED : DecisionType.ADJUSTMENT_EVALUATED,
                triggered ? DecisionOutcome.TRIGGERED : DecisionOutcome.SKIPPED,
                reasoning,
                context,
                triggered ? DecisionSeverity.INFO : DecisionSeverity.DEBUG);
    }

    // ---- Kill switch ----

    /**
     * Logs a kill switch activation.
     *
     * @param reasoning why the kill switch was activated
     * @param details additional context (e.g., who triggered it, affected strategies)
     */
    public void logKillSwitch(String reasoning, Map<String, Object> details) {
        log(
                DecisionSource.KILL_SWITCH,
                null,
                DecisionType.KILL_SWITCH_ACTIVATED,
                DecisionOutcome.TRIGGERED,
                reasoning,
                details,
                DecisionSeverity.CRITICAL);
    }

    // ---- System events ----

    /**
     * Logs a system-level informational event (session expired, stale data, etc.).
     *
     * @param decisionType the specific system event type
     * @param reasoning human-readable explanation
     * @param details additional context
     * @param severity the severity level
     */
    public void logSystemEvent(
            DecisionType decisionType, String reasoning, Map<String, Object> details, DecisionSeverity severity) {

        log(DecisionSource.SYSTEM, null, decisionType, DecisionOutcome.INFO, reasoning, details, severity);
    }

    // ---- Order events ----

    /**
     * Logs an order lifecycle event (placed, filled, rejected, timeout).
     *
     * @param orderId the order ID
     * @param decisionType ORDER_PLACED, ORDER_FILLED, ORDER_REJECTED, or ORDER_TIMEOUT
     * @param outcome the outcome (TRIGGERED for placed/filled, REJECTED/FAILED for errors)
     * @param reasoning human-readable explanation
     * @param details additional context (price, qty, etc.)
     */
    public void logOrderEvent(
            String orderId,
            DecisionType decisionType,
            DecisionOutcome outcome,
            String reasoning,
            Map<String, Object> details) {

        DecisionSeverity severity = (outcome == DecisionOutcome.REJECTED || outcome == DecisionOutcome.FAILED)
                ? DecisionSeverity.WARNING
                : DecisionSeverity.INFO;

        log(DecisionSource.ORDER_ROUTER, orderId, decisionType, outcome, reasoning, details, severity);
    }

    // ---- Strategy lifecycle ----

    /**
     * Logs a strategy lifecycle transition (deployed, armed, paused, closed).
     *
     * @param strategyId the strategy
     * @param decisionType STRATEGY_DEPLOYED, STRATEGY_ARMED, STRATEGY_PAUSED, or STRATEGY_CLOSED
     * @param reasoning human-readable explanation
     */
    public void logStrategyLifecycle(String strategyId, DecisionType decisionType, String reasoning) {
        log(
                DecisionSource.STRATEGY_ENGINE,
                strategyId,
                decisionType,
                DecisionOutcome.INFO,
                reasoning,
                null,
                DecisionSeverity.INFO);
    }

    // ---- Morph events ----

    /**
     * Logs a strategy morph execution or failure.
     *
     * @param sourceStrategyId the strategy being morphed
     * @param success whether the morph succeeded
     * @param reasoning human-readable explanation
     * @param details morph details (target type, legs closed/opened, etc.)
     */
    public void logMorph(String sourceStrategyId, boolean success, String reasoning, Map<String, Object> details) {

        log(
                DecisionSource.MORPH_SERVICE,
                sourceStrategyId,
                success ? DecisionType.MORPH_EXECUTED : DecisionType.MORPH_FAILED,
                success ? DecisionOutcome.TRIGGERED : DecisionOutcome.FAILED,
                reasoning,
                details,
                DecisionSeverity.INFO);
    }

    // ---- Risk limit breach ----

    /**
     * Logs a risk limit breach event.
     *
     * @param sourceId the entity that breached the limit (strategy ID, account, etc.)
     * @param reasoning description of the breach
     * @param details breach details (current value, limit, utilization, etc.)
     */
    public void logRiskBreach(String sourceId, String reasoning, Map<String, Object> details) {
        log(
                DecisionSource.RISK_MANAGER,
                sourceId,
                DecisionType.RISK_LIMIT_BREACH,
                DecisionOutcome.TRIGGERED,
                reasoning,
                details,
                DecisionSeverity.WARNING);
    }

    // ---- Ring buffer queries ----

    /**
     * Returns the most recent N decision records from the ring buffer.
     * Fast, no DB query -- reads from in-memory deque.
     */
    public List<DecisionRecord> getRecentDecisions(int count) {
        return ringBuffer.stream().limit(count).toList();
    }

    /**
     * Returns the most recent N decision records filtered by source.
     */
    public List<DecisionRecord> getRecentDecisions(int count, DecisionSource source) {
        return ringBuffer.stream()
                .filter(r -> r.getSource() == source)
                .limit(count)
                .toList();
    }

    /**
     * Returns the most recent N decision records filtered by severity.
     */
    public List<DecisionRecord> getRecentDecisions(int count, DecisionSeverity minSeverity) {
        return ringBuffer.stream()
                .filter(r -> r.getSeverity().ordinal() >= minSeverity.ordinal())
                .limit(count)
                .toList();
    }

    /**
     * Returns the current ring buffer size (for monitoring).
     */
    public int getBufferSize() {
        return ringBuffer.size();
    }

    // ---- Internal ----

    private void persist(DecisionRecord decisionRecord) {
        // Add to ring buffer (always, for real-time reads)
        ringBuffer.addFirst(decisionRecord);
        while (ringBuffer.size() > RING_BUFFER_SIZE) {
            ringBuffer.removeLast();
        }

        // Publish event for WebSocket streaming (Task 8.2 will listen)
        try {
            applicationEventPublisher.publishEvent(new DecisionLogEvent(this, decisionRecord));
        } catch (Exception e) {
            // Event publishing failure must never block the trading path
            logger.error("Failed to publish DecisionLogEvent: {}", e.getMessage());
        }

        // Queue for async H2 persistence
        decisionArchiveService.queue(decisionRecord);
    }

    // ---- Replay mode ----

    private volatile boolean replayMode = false;
    private volatile String replaySessionId;

    /**
     * Enables or disables replay mode. When enabled, all decision records produced
     * by this logger will be tagged with the replay sessionId, allowing later comparison
     * against live decisions for the same date.
     *
     * @param enabled   true to enable replay tagging, false to disable
     * @param sessionId the replay session ID (null when disabling)
     */
    public void setReplayMode(boolean enabled, String sessionId) {
        this.replayMode = enabled;
        this.replaySessionId = sessionId;
    }

    /**
     * Returns whether the logger is currently in replay mode.
     */
    public boolean isReplayMode() {
        return replayMode;
    }

    /**
     * Returns the current replay session ID, or null if not in replay mode.
     */
    public String getReplaySessionId() {
        return replaySessionId;
    }

    // ---- Visible for testing ----

    /**
     * Clears the ring buffer. Used in tests only.
     */
    void clearBuffer() {
        ringBuffer.clear();
    }
}
