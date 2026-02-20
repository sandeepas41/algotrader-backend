package com.algotrader.domain.model;

import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.vo.ChargeBreakdown;
import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * Simplified morph plan produced by {@link com.algotrader.morph.MorphTargetResolver}.
 *
 * <p>Bridges the gap between the FE's simple morph request (strategyId + targetType)
 * and the BE's detailed {@link MorphExecutionPlan}. Contains human-readable preview
 * data: which legs to keep/close and descriptive labels for new legs to open.
 *
 * <p>Used by the simplified morph endpoints on StrategyController to return
 * a preview matching the FE's expected response shape.
 */
@Getter
@Setter
@Builder
public class SimpleMorphPlan {

    private StrategyType targetType;

    /** Human-readable description of what the morph does (e.g., "Close call side, keep put spread"). */
    private String description;

    /** StrategyLeg entity IDs to retain in the new strategy. */
    private List<String> legsToKeep;

    /** StrategyLeg entity IDs to close (exit positions). */
    private List<String> legsToClose;

    /** Descriptive labels for new legs to open (e.g., "SELL CE ATM", "BUY PE OTM"). */
    private List<String> legsToOpen;

    /**
     * True when this morph requires new legs with strike resolution.
     * Rules 1-2 (pure retention) are false; rules 3-8 (new legs needed) are true.
     */
    private boolean requiresStrikeSelection;

    // #TODO: Compute estimated cost from current position P&L when market data is available
    private BigDecimal estimatedCost;

    // #TODO: Compute estimated charges from ChargeCalculator when available
    private ChargeBreakdown estimatedCharges;
}
