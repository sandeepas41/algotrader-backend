package com.algotrader.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a strategy from existing broker positions in a single call.
 * Deploys the strategy, adopts all specified positions, and activates it for monitoring.
 * No new orders are placed — the positions already exist at the broker.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeployWithAdoptionRequest {

    /** User-provided name for the strategy (e.g., "NIFTY Morning Strangle"). */
    @NotBlank
    private String name;

    /** Strategy type (e.g., "CUSTOM", "STRADDLE", "IRON_CONDOR"). */
    @NotBlank
    private String type;

    /** Root underlying symbol (e.g., "NIFTY", "BANKNIFTY", "ADANIPORTS"). */
    @NotBlank
    private String underlying;

    /** Expiry date for the strategy. */
    @NotNull
    private LocalDate expiry;

    /** Positions to adopt into the strategy. Each with positionId and signed quantity. */
    @NotEmpty
    @Valid
    private List<AdoptRequest> positions;
}
