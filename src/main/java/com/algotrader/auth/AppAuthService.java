package com.algotrader.auth;

import com.algotrader.exception.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Simple app-level authentication service using hardcoded admin/admin credentials and JWT tokens.
 *
 * <p>This is a lightweight gate to protect the trading UI from unauthorized access.
 * Kite broker auth (OAuth) is separate and runs on startup internally — this service
 * only handles the app login that guards the frontend.
 *
 * <p>JWT tokens are signed with HS256 using a configurable secret from application.properties.
 * Tokens expire after 24 hours.
 */
@Service
public class AppAuthService {

    private static final Logger log = LoggerFactory.getLogger(AppAuthService.class);
    private static final String HARDCODED_USERNAME = "admin";
    private static final String HARDCODED_PASSWORD = "admin";
    private static final Duration TOKEN_EXPIRY = Duration.ofHours(24);

    private final SecretKey secretKey;

    public AppAuthService(@Value("${algotrader.auth.jwt-secret}") String jwtSecret) {
        this.secretKey = new SecretKeySpec(jwtSecret.getBytes(), "HmacSHA256");
    }

    /**
     * Authenticates the given credentials against the hardcoded admin/admin pair.
     *
     * @return JWT token string if credentials match
     * @throws UnauthorizedException if credentials are invalid
     */
    public String authenticate(String username, String password) {
        if (!HARDCODED_USERNAME.equals(username) || !HARDCODED_PASSWORD.equals(password)) {
            log.warn("Failed login attempt for username: {}", username);
            throw new UnauthorizedException("Invalid username or password");
        }
        log.info("User '{}' authenticated successfully", username);
        return generateToken(username);
    }

    /**
     * Validates the given JWT token and returns the username (subject) if valid.
     *
     * @return the username from the token
     * @throws JwtException if the token is invalid, expired, or tampered
     */
    public String validateToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    /**
     * Returns the expiration instant for a given valid JWT token.
     */
    public Instant getTokenExpiry(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getExpiration().toInstant();
    }

    private String generateToken(String username) {
        Instant now = Instant.now();
        Instant expiry = now.plus(TOKEN_EXPIRY);

        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }
}
