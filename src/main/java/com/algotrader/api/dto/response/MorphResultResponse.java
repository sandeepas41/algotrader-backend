package com.algotrader.api.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO response for a completed morph operation.
 *
 * <p>Returned by the morph endpoint with a summary of what was done:
 * which legs were closed/reassigned/opened and the IDs of new strategies.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MorphResultResponse {
    private String sourceStrategyId;
    private List<String> newStrategyIds;
    private int legsClosedCount;
    private int legsReassignedCount;
    private int legsOpenedCount;
    private boolean success;
    private String errorMessage;
    private Long morphPlanId;
}
