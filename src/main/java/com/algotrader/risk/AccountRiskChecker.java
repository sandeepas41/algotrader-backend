package com.algotrader.risk;

import com.algotrader.event.RiskEvent;
import com.algotrader.event.RiskEventType;
import com.algotrader.event.RiskLevel;
import com.algotrader.oms.OrderRequest;
import com.algotrader.repository.redis.OrderRedisRepository;
import com.algotrader.repository.redis.PositionRedisRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Account-level risk checker enforcing daily loss limits, position/order counts,
 * margin utilization, and active strategy limits.
 *
 * <p>Called by RiskManager as part of the pre-trade validation pipeline. Also provides
 * real-time account limit monitoring via {@link #checkAccountLimits()}.
 *
 * <p><b>Thread safety:</b> The {@code dailyRealisedPnl} field is updated from
 * multiple threads (order fill events, position close events) and uses
 * {@link AtomicReference} with {@code updateAndGet} for lock-free thread safety.
 * The {@code currentDate} is volatile to ensure visibility across threads for
 * the daily reset check.
 *
 * <p>Margin utilization checks are deferred to Task 7.4 (MarginEngine) which
 * provides the actual margin data via BrokerGateway with caching.
 */
@Component
public class AccountRiskChecker {

    private static final Logger log = LoggerFactory.getLogger(AccountRiskChecker.class);

    private final RiskLimits riskLimits;
    private final PositionRedisRepository positionRedisRepository;
    private final OrderRedisRepository orderRedisRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    /** Thread-safe daily realized P&L counter, updated via AtomicReference. */
    private final AtomicReference<BigDecimal> dailyRealisedPnl = new AtomicReference<>(BigDecimal.ZERO);

    /** Volatile to ensure cross-thread visibility for the daily reset check. */
    private volatile LocalDate currentDate = LocalDate.now();

    public AccountRiskChecker(
            RiskLimits riskLimits,
            PositionRedisRepository positionRedisRepository,
            OrderRedisRepository orderRedisRepository,
            ApplicationEventPublisher applicationEventPublisher) {
        this.riskLimits = riskLimits;
        this.positionRedisRepository = positionRedisRepository;
        this.orderRedisRepository = orderRedisRepository;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    // ========================
    // PRE-TRADE VALIDATION
    // ========================

    /**
     * Validates an order against account-level risk limits.
     *
     * <p>Checks (all evaluated, not short-circuited):
     * <ol>
     *   <li>Daily loss limit breach (hard reject)</li>
     *   <li>Max open positions</li>
     *   <li>Max pending orders</li>
     * </ol>
     *
     * @param request the order to validate
     * @return list of violations (empty if order is within limits)
     */
    public List<RiskViolation> validateOrder(OrderRequest request) {
        resetDailyCountersIfNeeded();

        List<RiskViolation> violations = new ArrayList<>();

        // Daily loss limit
        if (isDailyLimitBreached()) {
            violations.add(RiskViolation.of(
                    "DAILY_LOSS_LIMIT_BREACHED",
                    "Daily loss limit has been breached. No new positions allowed. Current loss: "
                            + getDailyRealisedPnl()));
        }

        // Max open positions
        if (isMaxPositionsReached()) {
            violations.add(RiskViolation.of(
                    "MAX_POSITIONS_REACHED", "Maximum number of open positions reached: " + getOpenPositionCount()));
        }

        // Max pending orders
        if (isMaxOrdersReached()) {
            violations.add(RiskViolation.of(
                    "MAX_ORDERS_REACHED", "Maximum number of pending orders reached: " + getPendingOrderCount()));
        }

        return violations;
    }

    // ========================
    // REAL-TIME MONITORING
    // ========================

    /**
     * Checks account-level limits and publishes risk events for breaches and warnings.
     * Called periodically (e.g., every 30s) or on P&L change events.
     */
    public void checkAccountLimits() {
        resetDailyCountersIfNeeded();

        if (riskLimits.getDailyLossLimit() == null) {
            return;
        }

        BigDecimal dailyPnl = getDailyRealisedPnl();
        BigDecimal lossLimit = riskLimits.getDailyLossLimit().negate();

        // Check breach first (more severe)
        if (dailyPnl.compareTo(lossLimit) <= 0) {
            applicationEventPublisher.publishEvent(new RiskEvent(
                    this,
                    RiskEventType.DAILY_LOSS_LIMIT_BREACH,
                    RiskLevel.CRITICAL,
                    "Daily loss limit breached: " + dailyPnl));
            return;
        }

        // Check warning threshold
        if (riskLimits.getDailyLossWarningThreshold() != null) {
            BigDecimal warningThreshold = riskLimits
                    .getDailyLossLimit()
                    .multiply(riskLimits.getDailyLossWarningThreshold())
                    .negate();
            if (dailyPnl.compareTo(warningThreshold) <= 0) {
                applicationEventPublisher.publishEvent(new RiskEvent(
                        this,
                        RiskEventType.DAILY_LOSS_LIMIT_APPROACH,
                        RiskLevel.WARNING,
                        "Daily loss approaching limit: " + dailyPnl));
            }
        }
    }

    // ========================
    // DAILY P&L MANAGEMENT
    // ========================

    /**
     * Records a realized P&L amount to the daily running total.
     * Thread-safe via AtomicReference.updateAndGet().
     *
     * @param pnl the realized P&L to add (negative for losses)
     */
    public void recordRealisedPnl(BigDecimal pnl) {
        dailyRealisedPnl.updateAndGet(current -> current.add(pnl));
        log.debug("Recorded realized P&L: {}, daily total: {}", pnl, dailyRealisedPnl.get());
    }

    /**
     * Returns the current daily realized P&L.
     */
    public BigDecimal getDailyRealisedPnl() {
        return dailyRealisedPnl.get();
    }

    /**
     * Resets the daily P&L counter to a specific value.
     * Used for crash recovery (loading from Redis) and testing.
     */
    public void resetDailyPnl(BigDecimal value) {
        dailyRealisedPnl.set(value);
    }

    // ========================
    // LIMIT CHECKS
    // ========================

    /**
     * Returns true if the daily loss limit has been breached.
     * Breach: dailyRealisedPnl <= -(dailyLossLimit)
     */
    public boolean isDailyLimitBreached() {
        if (riskLimits.getDailyLossLimit() == null) {
            return false;
        }
        return dailyRealisedPnl.get().compareTo(riskLimits.getDailyLossLimit().negate()) <= 0;
    }

    /**
     * Returns true if the daily loss is approaching the configured warning threshold.
     */
    public boolean isDailyLimitApproaching() {
        if (riskLimits.getDailyLossLimit() == null || riskLimits.getDailyLossWarningThreshold() == null) {
            return false;
        }
        BigDecimal warningThreshold = riskLimits
                .getDailyLossLimit()
                .multiply(riskLimits.getDailyLossWarningThreshold())
                .negate();
        BigDecimal currentPnl = dailyRealisedPnl.get();
        return currentPnl.compareTo(warningThreshold) <= 0
                && currentPnl.compareTo(riskLimits.getDailyLossLimit().negate()) > 0;
    }

    /**
     * Returns true if the max open positions limit has been reached.
     */
    public boolean isMaxPositionsReached() {
        if (riskLimits.getMaxOpenPositions() == null) {
            return false;
        }
        return getOpenPositionCount() >= riskLimits.getMaxOpenPositions();
    }

    /**
     * Returns true if the max pending orders limit has been reached.
     */
    public boolean isMaxOrdersReached() {
        if (riskLimits.getMaxOpenOrders() == null) {
            return false;
        }
        return getPendingOrderCount() >= riskLimits.getMaxOpenOrders();
    }

    // ========================
    // QUERIES
    // ========================

    /**
     * Returns the current number of open positions (from Redis).
     */
    public int getOpenPositionCount() {
        return positionRedisRepository.findAll().size();
    }

    /**
     * Returns the current number of pending orders (from Redis).
     */
    public int getPendingOrderCount() {
        return orderRedisRepository.countPending();
    }

    // ========================
    // INTERNALS
    // ========================

    /**
     * Resets daily counters if the date has changed.
     * Uses volatile currentDate for cross-thread visibility.
     */
    void resetDailyCountersIfNeeded() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDate)) {
            dailyRealisedPnl.set(BigDecimal.ZERO);
            currentDate = today;
            log.info("Daily risk counters reset for {}", today);
        }
    }
}
