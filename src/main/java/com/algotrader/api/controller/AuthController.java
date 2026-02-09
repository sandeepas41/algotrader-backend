package com.algotrader.api.controller;

import com.algotrader.api.dto.request.AuthCallbackRequest;
import com.algotrader.api.dto.response.AuthCallbackResponse;
import com.algotrader.api.dto.response.AuthStatusResponse;
import com.algotrader.broker.KiteAuthService;
import jakarta.validation.Valid;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Kite Connect authentication endpoints.
 *
 * <p>Provides manual login flow (login URL + callback), on-demand re-authentication,
 * session status check, and logout. These endpoints are used by the frontend's broker
 * status widget and as a fallback when the automated sidecar login is unavailable.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /api/auth/login-url       - Returns the Kite OAuth login URL</li>
 *   <li>POST /api/auth/callback        - Handles the OAuth redirect with request_token</li>
 *   <li>POST /api/auth/re-authenticate - Triggers on-demand re-auth (via sidecar)</li>
 *   <li>GET  /api/auth/status          - Returns current authentication status</li>
 *   <li>POST /api/auth/logout          - Invalidates the Kite session and clears state</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final KiteAuthService kiteAuthService;

    public AuthController(KiteAuthService kiteAuthService) {
        this.kiteAuthService = kiteAuthService;
    }

    /**
     * Returns the Kite OAuth login URL for manual browser-based authentication.
     * The trader opens this URL in a browser, logs in, and is redirected back
     * with a request_token to the callback endpoint.
     */
    @GetMapping("/login-url")
    public ResponseEntity<Map<String, String>> getLoginUrl() {
        String loginUrl = kiteAuthService.getLoginUrl();
        return ResponseEntity.ok(Map.of("loginUrl", loginUrl));
    }

    /**
     * Handles the Kite OAuth callback after the trader completes manual login.
     * Exchanges the request_token for an access_token and persists the session.
     *
     * <p>The frontend sends a POST with JSON body containing the requestToken
     * extracted from the Kite redirect URL query parameters.
     */
    @PostMapping("/callback")
    public ResponseEntity<AuthCallbackResponse> handleCallback(@Valid @RequestBody AuthCallbackRequest request) {
        log.info("Received OAuth callback with request_token");
        kiteAuthService.handleCallback(request.getRequestToken());

        AuthCallbackResponse response = AuthCallbackResponse.builder()
                .userId(kiteAuthService.getCurrentUserId())
                .userName(kiteAuthService.getCurrentUserName())
                .sessionExpiresAt(kiteAuthService.getTokenExpiry())
                .build();
        return ResponseEntity.ok(response);
    }

    /**
     * Triggers on-demand re-authentication via the kite-login sidecar.
     * Uses the single-flight reauth gate to prevent concurrent reauth storms.
     */
    @PostMapping("/re-authenticate")
    public ResponseEntity<Map<String, String>> reAuthenticate() {
        log.info("On-demand re-authentication requested");
        kiteAuthService.reAuthenticate();
        return ResponseEntity.ok(Map.of("message", "Re-authentication successful"));
    }

    /**
     * Returns the current authentication status including user info and session expiry.
     * Used by the frontend dashboard to display broker connection state.
     */
    @GetMapping("/status")
    public ResponseEntity<AuthStatusResponse> getStatus() {
        AuthStatusResponse response = AuthStatusResponse.builder()
                .authenticated(kiteAuthService.isAuthenticated())
                .userId(kiteAuthService.getCurrentUserId())
                .userName(kiteAuthService.getCurrentUserName())
                .sessionExpiresAt(kiteAuthService.getTokenExpiry())
                .build();
        return ResponseEntity.ok(response);
    }

    /**
     * Logs out by invalidating the Kite access token and clearing local session state.
     * After logout, the trader must re-authenticate via the login flow.
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout() {
        log.info("Logout requested");
        kiteAuthService.logout();
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }
}
