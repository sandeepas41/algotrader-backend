package com.algotrader.domain.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Result of a morph operation, summarizing what was done.
 *
 * <p>Returned by MorphService.morph() and MorphService.preview().
 * For preview, the result describes the plan without actually executing it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MorphResult {

    private String sourceStrategyId;
    private List<String> newStrategyIds;
    private int legsClosedCount;
    private int legsReassignedCount;
    private int legsOpenedCount;
    private boolean success;
    private String errorMessage;

    /** The morph plan ID for tracking/querying. */
    private Long morphPlanId;
}
