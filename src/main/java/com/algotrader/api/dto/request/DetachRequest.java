package com.algotrader.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for detaching a position from a strategy.
 * Clears the positionId on the matching StrategyLeg, releasing the allocated quantity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetachRequest {

    /** The position ID to detach from the strategy. */
    @NotBlank
    private String positionId;
}
