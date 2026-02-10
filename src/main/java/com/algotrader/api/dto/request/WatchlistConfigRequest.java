package com.algotrader.api.dto.request;

import com.algotrader.domain.enums.ExpiryType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * REST API request DTO for creating or updating a watchlist subscription config.
 * Validated by Spring's @Valid annotation in the controller.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatchlistConfigRequest {

    /** Root underlying symbol (e.g., "NIFTY", "BANKNIFTY", "ADANIPORTS"). */
    @NotBlank
    private String underlying;

    /** Number of strikes above and below ATM to subscribe (1–30). */
    @Min(1)
    @Max(30)
    private int strikesFromAtm;

    /** Which expiry to auto-subscribe. */
    @NotNull
    private ExpiryType expiryType;

    /** Whether this config is active. */
    private Boolean enabled;
}
