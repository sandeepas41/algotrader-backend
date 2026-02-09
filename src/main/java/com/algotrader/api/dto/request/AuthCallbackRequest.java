package com.algotrader.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Request DTO for the POST /api/auth/callback endpoint.
 *
 * <p>Contains the request_token obtained from the Kite OAuth redirect.
 * The frontend extracts this from the redirect URL query parameters
 * and sends it to the backend for exchange into an access token.
 */
@Getter
@Setter
public class AuthCallbackRequest {

    @NotBlank(message = "requestToken is required")
    private String requestToken;
}
