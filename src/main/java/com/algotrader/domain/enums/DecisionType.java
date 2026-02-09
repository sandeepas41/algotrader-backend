package com.algotrader.domain.enums;

/**
 * Classifies the specific kind of trading decision that was made.
 *
 * <p>Grouped by decision source:
 * <ul>
 *   <li>STRATEGY_* -- Strategy lifecycle and evaluation decisions</li>
 *   <li>CONDITION_* -- Condition engine rule evaluations</li>
 *   <li>ADJUSTMENT_* -- Position adjustment decisions (inline in strategies)</li>
 *   <li>RISK_* -- Risk manager validation and breach decisions</li>
 *   <li>MORPH_* -- Strategy morphing (e.g., straddle -> strangle)</li>
 *   <li>System-level: kill switch, stale data, session, order lifecycle</li>
 * </ul>
 *
 * <p>The distinction between EVALUATED and TRIGGERED matters:
 * EVALUATED means the system checked the condition, TRIGGERED means the
 * condition was met and action was taken. This lets the decision log show
 * how many times a strategy was evaluated vs. how many times it actually acted.
 */
public enum DecisionType {
    // Strategy decisions
    STRATEGY_ENTRY_EVALUATED,
    STRATEGY_ENTRY_TRIGGERED,
    STRATEGY_ENTRY_SKIPPED,
    STRATEGY_EXIT_EVALUATED,
    STRATEGY_EXIT_TRIGGERED,
    STRATEGY_EXIT_SKIPPED,
    STRATEGY_DEPLOYED,
    STRATEGY_ARMED,
    STRATEGY_PAUSED,
    STRATEGY_CLOSED,

    // Condition decisions
    CONDITION_EVALUATED,
    CONDITION_TRIGGERED,
    CONDITION_SKIPPED,
    COMPOSITE_EVALUATED,

    // Adjustment decisions
    ADJUSTMENT_EVALUATED,
    ADJUSTMENT_TRIGGERED,
    ADJUSTMENT_EXECUTED,
    ADJUSTMENT_SKIPPED,

    // Risk decisions
    RISK_ORDER_VALIDATED,
    RISK_ORDER_REJECTED,
    RISK_LIMIT_BREACH,
    RISK_POSITION_FORCE_CLOSED,

    // Morph decisions
    MORPH_PLANNED,
    MORPH_EXECUTED,
    MORPH_FAILED,

    // Reconciliation decisions
    RECONCILIATION_RUN,
    RECONCILIATION_MISMATCH_RESOLVED,

    // Recovery decisions
    STARTUP_RECOVERY,
    GRACEFUL_SHUTDOWN,

    // System decisions
    KILL_SWITCH_ACTIVATED,
    STALE_DATA_DETECTED,
    SESSION_EXPIRED,
    ORDER_PLACED,
    ORDER_FILLED,
    ORDER_REJECTED,
    ORDER_TIMEOUT
}
