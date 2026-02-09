package com.algotrader.recovery;

import lombok.Builder;
import lombok.Data;

/**
 * WebSocket message sent to frontend before server shutdown so the UI can display
 * an appropriate maintenance message instead of raw connection errors.
 */
@Data
@Builder
public class ShutdownMessage {

    private String type;
    private String message;
    private long timestamp;
}
