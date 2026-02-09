package com.algotrader.domain.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * Result of a position reconciliation run comparing local state against broker state.
 *
 * <p>A matched result (no mismatches) confirms that our Redis-cached positions
 * are in sync with the broker. Mismatches trigger alerts and may cause the
 * RiskManager to pause strategies if critical discrepancies are detected.
 *
 * <p>Each run records the trigger (SCHEDULED, MANUAL, WEBSOCKET_RECONNECT, STARTUP),
 * position counts on both sides, mismatch details, resolution counters, and timing.
 */
@Data
@Builder
public class ReconciliationResult {

    private LocalDateTime timestamp;
    private String trigger;

    private int brokerPositionCount;
    private int localPositionCount;

    @Builder.Default
    private List<PositionMismatch> mismatches = new ArrayList<>();

    private int autoSynced;
    private int alertsRaised;
    private int strategiesPaused;
    private long durationMs;

    public boolean hasMismatches() {
        return mismatches != null && !mismatches.isEmpty();
    }

    public int getTotalMismatches() {
        return mismatches != null ? mismatches.size() : 0;
    }
}
