package com.algotrader.domain.enums;

/**
 * Identifies which system component produced a decision log entry.
 *
 * <p>Every automated decision is tagged with its source so the trader can
 * filter the decision log by subsystem (e.g., show only RISK_MANAGER decisions
 * to review why orders were rejected, or only STRATEGY_ENGINE to trace
 * entry/exit logic).
 *
 * <p>ADJUSTMENT is a logical source: adjustment logic is inline in each
 * strategy's {@code adjust()} method, not a separate engine, but we tag
 * its decisions separately for clarity in the decision log.
 */
public enum DecisionSource {
    STRATEGY_ENGINE,
    CONDITION_ENGINE,
    RISK_MANAGER,
    ADJUSTMENT,
    MORPH_SERVICE,
    KILL_SWITCH,
    ORDER_ROUTER,
    RECONCILIATION,
    RECOVERY,
    SYSTEM
}
