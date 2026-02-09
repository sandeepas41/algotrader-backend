package com.algotrader.api.dto.response;

import com.algotrader.domain.enums.StrategyType;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO response for a morph preview (execution plan without execution).
 *
 * <p>Returns a summary of what the morph will do: how many legs will be
 * closed, reassigned, and opened, plus target strategy types. Used by the
 * UI to show a confirmation dialog before actual execution.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MorphPreviewResponse {
    private String sourceStrategyId;
    private StrategyType sourceType;
    private int legsToCloseCount;
    private int legsToReassignCount;
    private int legsToOpenCount;
    private int strategiesToCreateCount;
    private List<StrategyType> targetTypes;
}
