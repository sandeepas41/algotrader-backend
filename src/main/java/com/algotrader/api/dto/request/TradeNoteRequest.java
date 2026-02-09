package com.algotrader.api.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating/updating trade journal notes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeNoteRequest {

    private String strategyId;
    private String runId;

    @NotBlank(message = "Note text is required")
    @Size(max = 2000, message = "Note must be 2000 characters or less")
    private String note;

    /** Comma-separated tags (e.g., "adjustment,delta-hedge,nifty"). */
    private String tags;

    @Min(value = 1, message = "Star rating must be between 1 and 5")
    @Max(value = 5, message = "Star rating must be between 1 and 5")
    private Integer starRating;

    private String marketCondition;
}
