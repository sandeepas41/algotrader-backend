package com.algotrader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the kite_session table.
 * Persists Kite Connect access tokens to H2 for recovery across restarts.
 * The primary session store is Redis (with TTL aligned to 6 AM IST token expiry);
 * this table serves as a fallback when Redis data is lost.
 */
@Entity
@Table(name = "kite_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KiteSessionEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "access_token", length = 255)
    private String accessToken;

    @Column(name = "user_name", length = 100)
    private String userName;

    @Column(name = "login_time")
    private LocalDateTime loginTime;

    /** Kite tokens expire at 6 AM IST next day. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
