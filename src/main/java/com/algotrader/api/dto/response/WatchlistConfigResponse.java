package com.algotrader.api.dto.response;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * REST API response DTO for a watchlist subscription configuration.
 * Returned by the watchlist config CRUD endpoints on WatchlistConfigController.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatchlistConfigResponse {

    private Long id;
    private String underlying;
    private int strikesFromAtm;
    private String expiryType;
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
