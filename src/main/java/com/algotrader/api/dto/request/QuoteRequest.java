package com.algotrader.api.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/**
 * Request payload for fetching live quotes for a batch of instrument tokens.
 *
 * <p>Used by the FE to seed initial LTP data on page load before WebSocket ticks arrive.
 * Kite supports max 500 instruments per call; the service handles batching internally.
 */
@Data
public class QuoteRequest {

    /** Instrument tokens to fetch quotes for. Max 1000 per request. */
    @NotEmpty(message = "instrumentTokens must not be empty")
    @Size(max = 1000, message = "instrumentTokens must not exceed 1000")
    private List<Long> instrumentTokens;
}
