package com.algotrader.strategy.adoption;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * Result of a position adoption or detach operation.
 *
 * <p>Captures the outcome including any warnings that the caller (API layer)
 * should surface to the user. Warnings do NOT prevent the operation but indicate
 * potential issues:
 * <ul>
 *   <li><b>Quantity mismatch:</b> Position quantity doesn't match strategy's expected lot size</li>
 *   <li><b>Option type mismatch:</b> Position's option type may be incompatible with strategy</li>
 * </ul>
 *
 * <p>The recalculated entry premium is included when available, so the API can
 * return it to the frontend for immediate display.
 */
@Getter
@Builder
public class AdoptionResult {

    /** The strategy ID involved. */
    private final String strategyId;

    /** The position ID involved. */
    private final String positionId;

    /** Whether the operation was adopt or detach. */
    private final OperationType operationType;

    /** Recalculated entry premium after adoption. Null for detach. */
    private final BigDecimal recalculatedEntryPremium;

    /** Warnings to surface in the API response / UI. Empty if no warnings. */
    @Builder.Default
    private final List<String> warnings = new ArrayList<>();

    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }

    public enum OperationType {
        ADOPT,
        DETACH
    }
}
