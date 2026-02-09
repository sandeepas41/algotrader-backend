package com.algotrader.recovery;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * Captures the outcome of the daily startup recovery sequence.
 *
 * <p>Each of the 7 startup steps (token, instruments, state, journal, morph,
 * positions, strategies) records its result here. Published via SystemReadyEvent
 * so downstream components know what state the system is in after recovery.
 */
@Data
@Builder
public class RecoveryResult {

    private boolean success;
    private long startedAt;
    private long durationMs;
    private String error;

    // Step 1: Token acquisition
    private boolean tokenAcquired;

    // Step 2: Instrument download
    private boolean instrumentsLoaded;

    // Step 3: State restoration
    private BigDecimal restoredDailyPnL;
    private boolean killSwitchWasActive;

    // Step 4: Execution journal recovery
    private int incompleteExecutionsFound;

    @Builder.Default
    private List<String> recoveredExecutionGroups = new ArrayList<>();

    // Step 5: Position reconciliation
    private int positionsSynced;
    private boolean positionReconciliationFailed;

    // Step 6: Strategy resumption
    private int strategiesResumed;
}
