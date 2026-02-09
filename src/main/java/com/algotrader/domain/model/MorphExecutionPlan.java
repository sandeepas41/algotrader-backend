package com.algotrader.domain.model;

import com.algotrader.domain.enums.StrategyType;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The detailed execution plan for a morph operation.
 *
 * <p>Generated before execution begins, this plan enumerates every step:
 * which legs to close, which to reassign to new strategies, which new legs
 * to open, and which new strategy instances to create. The plan is serialized
 * to JSON and persisted as a WAL entry for crash recovery.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MorphExecutionPlan {

    private String sourceStrategyId;
    private StrategyType sourceType;

    /** Legs from source to close (market orders). */
    private List<LegCloseStep> legsToClose;

    /** Legs from source to reassign to a new strategy (no market order needed). */
    private List<LegReassignStep> legsToReassign;

    /** New legs to open (new positions). */
    private List<LegOpenStep> legsToOpen;

    /** New strategy instances to create after leg operations complete. */
    private List<StrategyCreateStep> strategiesToCreate;
}
