package com.algotrader.entity;

import com.algotrader.domain.enums.ExpiryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the watchlist_configs table.
 *
 * <p>Stores pre-configured instrument subscriptions that are auto-subscribed on
 * startup. Each config specifies an underlying (e.g., "NIFTY", "BANKNIFTY"),
 * the number of strikes around ATM to subscribe, and which expiry to use.
 *
 * <p>On application startup, enabled configs are read by WatchlistSubscriptionService
 * which resolves the nearest expiry, finds ATM, and subscribes spot + FUT + ATM±N
 * option tokens via InstrumentSubscriptionManager.
 */
@Entity
@Table(name = "watchlist_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatchlistConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Root underlying symbol (e.g., "NIFTY", "BANKNIFTY", "ADANIPORTS"). */
    @Column(nullable = false, length = 20)
    private String underlying;

    /** Number of strikes above and below ATM to subscribe (e.g., 10 means ±10 strikes). */
    @Builder.Default
    @Column(name = "strikes_from_atm", nullable = false)
    private int strikesFromAtm = 10;

    /** Which expiry to auto-subscribe: nearest weekly or nearest monthly. */
    @Enumerated(EnumType.STRING)
    @Column(name = "expiry_type", nullable = false, columnDefinition = "varchar(50)")
    private ExpiryType expiryType;

    /** Whether this config is active and should be auto-subscribed on startup. */
    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
