package com.algotrader.margin;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.domain.model.AccountMargin;
import com.algotrader.exception.BrokerException;
import com.algotrader.exception.SessionExpiredException;
import com.algotrader.session.SessionHealthService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fetches and caches real-time margin data from the broker.
 *
 * <p>Implements a lightweight 30-second TTL cache using an {@link AtomicReference} around
 * {@link CachedMargin}. This avoids excessive Kite API calls while keeping margin data
 * reasonably fresh for the {@link MarginMonitorService} and {@link MarginEstimator}.
 *
 * <p>Cache behavior:
 * <ul>
 *   <li>Within TTL: returns cached value immediately (no API call)</li>
 *   <li>Expired + session active: fetches fresh data from broker</li>
 *   <li>Expired + session inactive: returns stale data with warning (graceful degradation)</li>
 *   <li>No cached data + session inactive: throws SessionExpiredException</li>
 *   <li>API failure: returns stale data if available, otherwise throws BrokerException</li>
 * </ul>
 *
 * <p>The cache is invalidated explicitly after order fills via {@link #invalidateCache()},
 * ensuring post-trade margin checks use fresh data.
 */
@Service
public class MarginService {

    private static final Logger log = LoggerFactory.getLogger(MarginService.class);

    private final BrokerGateway brokerGateway;
    private final SessionHealthService sessionHealthService;

    private final AtomicReference<CachedMargin> cachedMargin = new AtomicReference<>();

    public MarginService(BrokerGateway brokerGateway, SessionHealthService sessionHealthService) {
        this.brokerGateway = brokerGateway;
        this.sessionHealthService = sessionHealthService;
    }

    /**
     * Returns current account margins, served from cache if within 30s TTL.
     *
     * @return current account margin snapshot
     * @throws SessionExpiredException if session is not active and no cached data exists
     * @throws BrokerException if the broker API call fails and no cached data exists
     */
    public AccountMargin getMargins() {
        CachedMargin cached = cachedMargin.get();

        if (cached != null && !cached.isExpired()) {
            return cached.getMargin();
        }

        if (!sessionHealthService.isSessionActive()) {
            if (cached != null) {
                log.warn("Session not active, returning stale margin data (cached at {})", cached.getCachedAt());
                return cached.getMargin();
            }
            throw new SessionExpiredException("Cannot fetch margins: session not active");
        }

        try {
            Map<String, BigDecimal> kiteMargins = brokerGateway.getMargins();

            BigDecimal availableMargin = kiteMargins.getOrDefault("available", BigDecimal.ZERO);
            BigDecimal usedMargin = kiteMargins.getOrDefault("used", BigDecimal.ZERO);
            BigDecimal totalCapital = availableMargin.add(usedMargin);

            AccountMargin margin = AccountMargin.builder()
                    .availableCash(kiteMargins.getOrDefault("cash", BigDecimal.ZERO))
                    .availableMargin(availableMargin)
                    .usedMargin(usedMargin)
                    .collateral(kiteMargins.getOrDefault("collateral", BigDecimal.ZERO))
                    .totalCapital(totalCapital)
                    .utilizationPercent(calculateUtilization(usedMargin, totalCapital))
                    .fetchedAt(Instant.now())
                    .build();

            cachedMargin.set(new CachedMargin(margin, Instant.now()));
            return margin;

        } catch (Exception e) {
            log.error("Failed to fetch margins from broker", e);
            if (cached != null) {
                log.warn("Returning stale margin data due to broker error");
                return cached.getMargin();
            }
            throw new BrokerException("Failed to fetch margins", e);
        }
    }

    /**
     * Returns the available margin for new trades.
     */
    public BigDecimal getAvailableMargin() {
        return getMargins().getAvailableMargin();
    }

    /**
     * Returns current margin utilization as a percentage (0-100).
     */
    public BigDecimal getUtilizationPercent() {
        return getMargins().getUtilizationPercent();
    }

    /**
     * Invalidates the margin cache, forcing the next call to fetch fresh data.
     * Called after order fills to ensure post-trade margin checks use current data.
     */
    public void invalidateCache() {
        cachedMargin.set(null);
        log.debug("Margin cache invalidated");
    }

    private BigDecimal calculateUtilization(BigDecimal used, BigDecimal total) {
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return used.divide(total, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
