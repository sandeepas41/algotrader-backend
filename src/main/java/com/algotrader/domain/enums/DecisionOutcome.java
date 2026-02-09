package com.algotrader.domain.enums;

/**
 * The result of a decision evaluation.
 *
 * <p>Used to quickly filter the decision log:
 * <ul>
 *   <li>TRIGGERED -- The condition was met and action was taken (entry, exit, adjustment)</li>
 *   <li>SKIPPED -- The condition was evaluated but no action was needed (normal cycle)</li>
 *   <li>REJECTED -- The action was blocked (e.g., by risk limits, kill switch)</li>
 *   <li>FAILED -- The action was attempted but failed (e.g., order placement error)</li>
 *   <li>INFO -- Informational entry, no action involved (session events, state changes)</li>
 * </ul>
 */
public enum DecisionOutcome {
    TRIGGERED,
    SKIPPED,
    REJECTED,
    FAILED,
    INFO
}
