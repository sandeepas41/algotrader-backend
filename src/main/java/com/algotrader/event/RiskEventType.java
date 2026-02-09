package com.algotrader.event;

/**
 * Classifies the type of risk condition that triggered a {@link RiskEvent}.
 *
 * <p>Each type corresponds to a specific risk check performed by the RiskManager
 * or KillSwitch. Listeners can filter on event type to take appropriate action
 * (e.g., pause strategies on DAILY_LOSS_LIMIT_BREACH but only log for
 * MARGIN_UTILIZATION_HIGH).
 */
public enum RiskEventType {

    /** A single position exceeds its configured loss limit. */
    POSITION_LIMIT_BREACH,

    /** Daily cumulative loss is approaching the configured limit (warning threshold). */
    DAILY_LOSS_LIMIT_APPROACH,

    /** Daily cumulative loss has exceeded the configured limit. */
    DAILY_LOSS_LIMIT_BREACH,

    /** Margin utilization has exceeded the warning threshold (e.g., 80%). */
    MARGIN_UTILIZATION_HIGH,

    /** Maximum number of concurrent positions has been reached. */
    MAX_POSITIONS_REACHED,

    /** Kill switch was activated — all orders cancelled, all positions being exited. */
    KILL_SWITCH_TRIGGERED
}
