package com.algotrader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the decision_log table.
 *
 * <p>Persists every structured trading decision with its source, type, outcome,
 * reasoning, and market context snapshot. Used for post-session analysis: reviewing
 * why the system entered, exited, adjusted, or rejected trades.
 *
 * <p>The table uses enum-typed columns (source, decisionType, outcome, severity)
 * for efficient filtering and aggregation queries, plus a JSON column (dataContext)
 * for variable structured data that differs by decision type.
 *
 * <p>Persistence is async via {@code DecisionArchiveService} to avoid blocking
 * the trading path. DEBUG-severity entries are only persisted when the persist-debug
 * toggle is enabled at runtime.
 */
@Entity
@Table(name = "decision_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DecisionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, columnDefinition = "varchar(50)")
    private com.algotrader.domain.enums.DecisionSource source;

    /** Strategy ID, rule ID, order ID -- correlates the decision to its entity. */
    @Column(name = "source_id", length = 100)
    private String sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_type", nullable = false, columnDefinition = "varchar(100)")
    private com.algotrader.domain.enums.DecisionType decisionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, columnDefinition = "varchar(50)")
    private com.algotrader.domain.enums.DecisionOutcome outcome;

    /** Human-readable explanation of why this decision was made. */
    @Column(name = "reasoning", nullable = false, columnDefinition = "TEXT")
    private String reasoning;

    /** JSON snapshot of relevant data at decision time (spot, IV, violations, etc.). */
    @Column(name = "data_context", columnDefinition = "TEXT")
    private String dataContext;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", columnDefinition = "varchar(50)")
    private com.algotrader.domain.enums.DecisionSeverity severity;

    /** Trading session date for grouping and archival queries. */
    @Column(name = "session_date")
    private LocalDate sessionDate;
}
