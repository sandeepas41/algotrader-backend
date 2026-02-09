package com.algotrader.api.dto.response;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Response DTO for successful app-level login.
 * Contains the JWT token, username, and token expiration timestamp.
 */
@Getter
@Builder
@AllArgsConstructor
public class LoginResponse {

    private final String token;
    private final String username;
    private final Instant expiresAt;
}
