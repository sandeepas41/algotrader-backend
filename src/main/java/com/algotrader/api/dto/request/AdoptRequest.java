package com.algotrader.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for adopting a broker position into a strategy.
 * Creates a new StrategyLeg linked to the position with the specified quantity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdoptRequest {

    /** The position ID to adopt (e.g., "NFO:NIFTY2560519500CE"). */
    @NotBlank
    private String positionId;

    /**
     * The quantity to allocate to this strategy (signed: negative = short, positive = long).
     * Must match the position's sign direction and not exceed the unmanaged remainder.
     */
    @NotNull
    private Integer quantity;
}
