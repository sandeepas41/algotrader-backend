package com.algotrader.margin;

import com.algotrader.domain.model.AccountMargin;
import java.time.Duration;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Wraps an {@link AccountMargin} snapshot with a timestamp for TTL-based cache expiry.
 *
 * <p>Used internally by {@link MarginService} to implement a lightweight
 * AtomicReference-based cache with a 30-second TTL. This avoids the overhead
 * of a Caffeine cache for a single frequently-refreshed value.
 */
@Data
@AllArgsConstructor
class CachedMargin {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final AccountMargin margin;
    private final Instant cachedAt;

    /**
     * Returns true if this cached value is older than the 30-second TTL.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(cachedAt.plus(TTL));
    }
}
