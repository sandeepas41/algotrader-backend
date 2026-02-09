package com.algotrader.domain.model;

import com.algotrader.domain.enums.StrategyType;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A step in the morph plan that creates a new strategy instance.
 * Created after all leg close/open operations complete successfully.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyCreateStep {
    private String newStrategyId;
    private StrategyType strategyType;
    /** Lineage link to the morphed source strategy. */
    private String parentStrategyId;

    private Map<String, Object> parameters;
    private List<String> assignedPositionIds;
}
