package com.algotrader.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A step in the morph plan that reassigns a leg from the source strategy
 * to a new target strategy. No market order is needed -- only the position's
 * strategyId is updated. This avoids unnecessary transaction costs.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LegReassignStep {
    private String positionId;
    private Long instrumentToken;
    private String tradingSymbol;
    private String fromStrategyId;
    private String toStrategyId;
    private boolean copyEntryPrice;
}
