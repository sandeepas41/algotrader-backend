package com.algotrader.api.dto.response;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

/**
 * Response DTO for the POST /api/auth/callback endpoint.
 *
 * <p>Returned after successfully exchanging a request_token for an access token.
 * Contains the authenticated user's identity and session expiry so the frontend
 * can populate the auth store and display the user's name.
 */
@Getter
@Builder
public class AuthCallbackResponse {

    private final String userId;
    private final String userName;
    private final LocalDateTime sessionExpiresAt;
}
