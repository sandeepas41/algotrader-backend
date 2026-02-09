package com.algotrader.domain.enums;

/**
 * What action to take when an adjustment rule triggers.
 * ROLL_UP/DOWN/OUT shift strikes or expiry. ADD_HEDGE adds protection.
 * CLOSE_ALL exits the entire strategy (used by kill switch and max-loss rules).
 * ALERT_ONLY sends a notification without modifying positions.
 */
public enum ActionType {
    ROLL_UP,
    ROLL_DOWN,
    ROLL_OUT,
    ADD_HEDGE,
    REDUCE_SIZE,
    CLOSE_LEG,
    CLOSE_ALL,
    ALERT_ONLY
}
