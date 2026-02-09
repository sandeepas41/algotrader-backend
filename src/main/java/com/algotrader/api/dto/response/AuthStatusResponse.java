package com.algotrader.api.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

/**
 * Response DTO for the GET /api/auth/status endpoint.
 *
 * <p>Provides the current Kite authentication state including whether a valid
 * token exists, the authenticated user ID, and session expiry time. Used by the
 * frontend dashboard to show broker connection status.
 *
 * <p>Field names match the frontend's AuthStatusResponse interface:
 * isAuthenticated, userId, userName, sessionExpiresAt.
 */
@Getter
@Builder
public class AuthStatusResponse {

    // Lombok generates isAuthenticated() getter for boolean; Jackson serializes as "authenticated".
    // @JsonProperty forces the JSON key to "isAuthenticated" to match the FE contract.
    @JsonProperty("isAuthenticated")
    private final boolean authenticated;

    private final String userId;
    private final String userName;
    private final LocalDateTime sessionExpiresAt;
}
