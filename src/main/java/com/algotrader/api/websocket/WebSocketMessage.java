package com.algotrader.api.websocket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standard WebSocket message envelope sent to frontend clients.
 *
 * <p>All WebSocket messages follow the contract {@code { type, data }} where:
 * <ul>
 *   <li>{@code type} -- a string identifying the kind of update (e.g., "ORDER", "POSITION",
 *       "TICK", "DECISION", "RISK", "STRATEGY", "SYSTEM")</li>
 *   <li>{@code data} -- the payload object, serialized as JSON</li>
 * </ul>
 *
 * <p>This uniform envelope lets the frontend route messages through a single
 * WebSocket subscription and dispatch based on type, rather than subscribing
 * to multiple topics for different event types.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketMessage {

    /** Message type identifier (e.g., "ORDER", "POSITION", "TICK", "DECISION"). */
    private String type;

    /** The payload data. Serialized to JSON by Spring's message converter. */
    private Object data;

    public static WebSocketMessage of(String type, Object data) {
        return new WebSocketMessage(type, data);
    }
}
