package com.algotrader.risk;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * Result of a kill switch activation.
 *
 * <p>Reports the outcome of each step: strategies paused, orders cancelled,
 * positions closed. Also carries any errors encountered during execution
 * (kill switch best-efforts all operations and reports failures).
 */
@Data
@Builder
public class KillSwitchResult {

    private boolean success;
    private int strategiesPaused;
    private int ordersCancelled;
    private int positionsClosed;
    private String reason;

    @Builder.Default
    private List<String> errors = new ArrayList<>();

    @Builder.Default
    private LocalDateTime activatedAt = LocalDateTime.now();

    public static KillSwitchResult alreadyActive() {
        return KillSwitchResult.builder()
                .success(false)
                .reason("Kill switch already active")
                .build();
    }

    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }
}
