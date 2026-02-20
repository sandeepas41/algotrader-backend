package com.algotrader.api.dto.response;

import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.vo.ChargeBreakdown;
import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Simplified morph preview response matching the FE {@code MorphPreview} interface.
 *
 * <p>Returned by {@code GET /api/strategies/{id}/morph/preview?targetType=X}.
 * Shows which legs will be kept, closed, and opened, along with cost estimates.
 * Separate from the existing {@link MorphPreviewResponse} which serves the
 * advanced morph API with execution plan counts.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimpleMorphPreviewResponse {

    private StrategyType targetType;
    private String description;

    /** StrategyLeg entity IDs that will be retained. */
    private List<String> legsToKeep;

    /** StrategyLeg entity IDs that will be closed. */
    private List<String> legsToClose;

    /** Descriptive labels for new legs to open (e.g., "SELL CE ATM"). */
    private List<String> legsToOpen;

    private BigDecimal estimatedCost;
    private ChargeBreakdown estimatedCharges;

    /** True when this morph needs strike selection before execution. */
    private boolean requiresStrikeSelection;
}
