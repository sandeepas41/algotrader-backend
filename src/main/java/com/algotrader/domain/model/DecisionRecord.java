package com.algotrader.domain.model;

import com.algotrader.domain.enums.DecisionOutcome;
import com.algotrader.domain.enums.DecisionSeverity;
import com.algotrader.domain.enums.DecisionSource;
import com.algotrader.domain.enums.DecisionType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/**
 * Domain model for a structured decision log entry.
 *
 * <p>Every automated trading decision (strategy evaluation, risk check, adjustment,
 * morph, kill switch) is captured as a DecisionRecord with full reasoning and market
 * context. This enables post-session analysis: reviewing what the system evaluated,
 * what it decided, and why.
 *
 * <p>The DecisionLogger service creates these records and stores them in a ring buffer
 * (for fast in-memory reads), publishes them as DecisionLogEvent (for WebSocket streaming),
 * and persists them to H2 (async, via DecisionArchiveService).
 *
 * <p>Key fields:
 * <ul>
 *   <li>{@code source} -- which subsystem made the decision</li>
 *   <li>{@code sourceId} -- strategy ID, rule ID, or order ID (for correlation)</li>
 *   <li>{@code decisionType} -- what kind of decision (entry, exit, adjustment, etc.)</li>
 *   <li>{@code outcome} -- what happened (triggered, skipped, rejected, failed)</li>
 *   <li>{@code reasoning} -- human-readable explanation</li>
 *   <li>{@code dataContext} -- structured snapshot of relevant data at decision time</li>
 * </ul>
 */
@Data
@Builder
public class DecisionRecord {

    private Long id;

    private LocalDateTime timestamp;

    private DecisionSource source;

    /** Strategy ID, rule ID, order ID -- whatever entity this decision relates to. */
    private String sourceId;

    private DecisionType decisionType;

    private DecisionOutcome outcome;

    /** Human-readable explanation of why this decision was made. */
    private String reasoning;

    /**
     * Structured data snapshot at decision time. Serialized to JSON for persistence.
     * Example keys: spotPrice, atmIV, violations, orderId, indicatorValue.
     */
    private Map<String, Object> dataContext;

    private DecisionSeverity severity;

    /** Trading session date (for grouping and archival queries). */
    private LocalDate sessionDate;

    /** Replay session ID, set when this decision was made during a tick replay. Null for live decisions. */
    private String replaySessionId;
}
