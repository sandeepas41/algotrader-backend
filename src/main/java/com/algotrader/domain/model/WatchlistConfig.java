package com.algotrader.domain.model;

import com.algotrader.domain.enums.ExpiryType;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Domain model for a watchlist subscription configuration.
 *
 * <p>Defines which underlying instruments to auto-subscribe on startup, how many
 * strikes around ATM to include, and which expiry to target. Used by
 * WatchlistSubscriptionService to resolve tokens and subscribe via
 * InstrumentSubscriptionManager with MANUAL priority.
 *
 * <p>Example: underlying="NIFTY", strikesFromAtm=10, expiryType=NEAREST_WEEKLY
 * means subscribe NIFTY spot + nearest weekly FUT + ATM ±10 strikes (CE+PE).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatchlistConfig {

    private Long id;

    /** Root underlying symbol (e.g., "NIFTY", "BANKNIFTY", "ADANIPORTS"). */
    private String underlying;

    /** Number of strikes above and below ATM to subscribe. */
    @Builder.Default
    private int strikesFromAtm = 10;

    /** Which expiry to auto-subscribe: nearest weekly or nearest monthly. */
    private ExpiryType expiryType;

    /** Whether this config is active. */
    @Builder.Default
    private boolean enabled = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
