package com.algotrader.api.dto.response;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

/**
 * Response DTO for the GET /api/auth/status endpoint.
 *
 * <p>Provides the current Kite authentication state including whether a valid
 * token exists, the authenticated user ID, and token expiry time. Used by the
 * frontend dashboard to show broker connection status.
 */
@Getter
@Builder
public class AuthStatusResponse {

    private final boolean authenticated;
    private final String userId;
    private final String userName;
    private final LocalDateTime tokenExpiry;
}
