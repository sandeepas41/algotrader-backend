package com.algotrader.api.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO response for a full lineage tree (ancestors + descendants).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyLineageTreeResponse {
    private String strategyId;
    private List<StrategyLineageResponse> ancestors;
    private List<StrategyLineageResponse> descendants;
}
