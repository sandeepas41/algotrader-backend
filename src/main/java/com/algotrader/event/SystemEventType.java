package com.algotrader.event;

/**
 * Classifies the type of application lifecycle event that triggered a {@link SystemEvent}.
 *
 * <p>System events coordinate startup/shutdown behavior across components.
 * APPLICATION_READY triggers startup recovery, SHUTTING_DOWN triggers state persistence,
 * and BROKER_DISCONNECTED/CONNECTED control strategy execution.
 */
public enum SystemEventType {

    /** All subsystems initialized and ready — triggers startup recovery. */
    APPLICATION_READY,

    /** Graceful shutdown initiated — components should persist in-memory state. */
    SHUTTING_DOWN,

    /** Kite API connection established (REST + WebSocket). */
    BROKER_CONNECTED,

    /** Kite API connection lost (REST errors or WebSocket disconnect). */
    BROKER_DISCONNECTED
}
