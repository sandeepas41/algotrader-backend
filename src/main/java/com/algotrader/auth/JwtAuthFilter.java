package com.algotrader.auth;

import com.algotrader.api.dto.response.ApiErrorResponse;
import com.algotrader.exception.ErrorCode;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * JWT authentication filter that intercepts all /api/** requests.
 *
 * <p>Skips authentication for the login endpoint (/api/auth/login) and actuator endpoints.
 * For all other /api/** requests, it reads the Authorization header, validates the JWT token
 * via {@link AppAuthService}, and returns a 401 JSON error if the token is missing or invalid.
 *
 * <p>Registered as a servlet filter via {@link com.algotrader.config.WebConfig}.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final AppAuthService appAuthService;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(AppAuthService appAuthService, ObjectMapper objectMapper) {
        this.appAuthService = appAuthService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/auth/login") || path.startsWith("/actuator") || path.startsWith("/api/debug");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response, request.getRequestURI(), "Missing or invalid Authorization header");
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        try {
            String username = appAuthService.validateToken(token);
            // Store username in request attribute for downstream use if needed
            request.setAttribute("authenticatedUser", username);
            filterChain.doFilter(request, response);
        } catch (JwtException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            writeUnauthorized(response, request.getRequestURI(), "Invalid or expired token");
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String path, String message) throws IOException {
        ApiErrorResponse errorResponse = ApiErrorResponse.of(ErrorCode.UNAUTHORIZED, message, Map.of(), path);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}
