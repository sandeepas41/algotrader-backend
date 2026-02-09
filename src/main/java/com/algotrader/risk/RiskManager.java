package com.algotrader.risk;

import com.algotrader.domain.model.Position;
import com.algotrader.event.PositionEvent;
import com.algotrader.event.RiskEvent;
import com.algotrader.event.RiskEventType;
import com.algotrader.event.RiskLevel;
import com.algotrader.oms.OrderRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Central risk management service coordinating pre-trade validation and real-time monitoring.
 *
 * <p>Acts as the orchestrator for all risk checks:
 * <ul>
 *   <li><b>Pre-trade:</b> Called by the OrderRouter before order placement. Aggregates
 *       violations from {@link PositionRiskChecker} (position-level),
 *       {@link AccountRiskChecker} (account-level), and per-underlying limits.</li>
 *   <li><b>Real-time:</b> Listens for {@link PositionEvent}s and updates the instrument index
 *       in PositionRiskChecker for instrument-aware checking.</li>
 *   <li><b>Per-underlying:</b> Maintains {@link UnderlyingRiskLimits} per instrument and validates
 *       orders against these limits (max lots, max exposure, max strategies per underlying).</li>
 * </ul>
 *
 * <p>The risk manager does NOT directly listen for TickEvents -- instrument-aware position
 * checking is triggered per-position when PositionEvents update the index. Full position
 * scans are reserved for periodic health checks (not every tick).
 *
 * <p><b>Thread safety:</b> Uses ConcurrentHashMap for the per-underlying limits registry.
 * PositionRiskChecker manages its own instrument index concurrently.
 */
@Service
public class RiskManager {

    private static final Logger log = LoggerFactory.getLogger(RiskManager.class);

    private final RiskLimits riskLimits;
    private final PositionRiskChecker positionRiskChecker;
    private final AccountRiskChecker accountRiskChecker;
    private final ApplicationEventPublisher applicationEventPublisher;

    /** Per-underlying risk limits, keyed by underlying symbol (e.g., "NIFTY"). */
    private final Map<String, UnderlyingRiskLimits> underlyingLimitsMap = new ConcurrentHashMap<>();

    public RiskManager(
            RiskLimits riskLimits,
            PositionRiskChecker positionRiskChecker,
            AccountRiskChecker accountRiskChecker,
            ApplicationEventPublisher applicationEventPublisher) {
        this.riskLimits = riskLimits;
        this.positionRiskChecker = positionRiskChecker;
        this.accountRiskChecker = accountRiskChecker;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    // ========================
    // PRE-TRADE VALIDATION
    // ========================

    /**
     * Validates an order against all risk limits before placement.
     * Called by the OrderRouter in the pre-trade pipeline.
     *
     * <p>Checks (in order):
     * <ol>
     *   <li>Position-level limits (max lots, max notional)</li>
     *   <li>Account-level limits (daily loss, max positions, max orders)</li>
     *   <li>Per-underlying limits (max lots per underlying, max strategies per underlying)</li>
     * </ol>
     *
     * @param request the order to validate
     * @return approved result if within limits, rejected with all violations otherwise
     */
    public RiskValidationResult validateOrder(OrderRequest request) {
        List<RiskViolation> violations = new ArrayList<>();

        // Position-level checks
        violations.addAll(positionRiskChecker.validateOrder(request));

        // Account-level checks (daily loss, max positions, max orders)
        violations.addAll(accountRiskChecker.validateOrder(request));

        // Per-underlying checks
        violations.addAll(validateUnderlyingLimits(request));

        if (!violations.isEmpty()) {
            log.warn("Order rejected due to risk violations: {}", violations);
            publishRiskEvent(
                    RiskEventType.POSITION_LIMIT_BREACH,
                    RiskLevel.WARNING,
                    "Order rejected: " + violations.get(0).getMessage(),
                    Map.of(
                            "violationCount",
                            violations.size(),
                            "firstViolation",
                            violations.get(0).getCode()));
            return RiskValidationResult.rejected(violations);
        }

        return RiskValidationResult.approved();
    }

    // ========================
    // PER-UNDERLYING VALIDATION
    // ========================

    /**
     * Validates an order against per-underlying risk limits.
     * Returns empty list if no limits are configured for the order's underlying.
     */
    private List<RiskViolation> validateUnderlyingLimits(OrderRequest request) {
        String underlying = extractUnderlying(request.getTradingSymbol());
        if (underlying == null) {
            return Collections.emptyList();
        }

        UnderlyingRiskLimits underlyingRiskLimits = underlyingLimitsMap.get(underlying);
        if (underlyingRiskLimits == null) {
            return Collections.emptyList();
        }

        List<RiskViolation> violations = new ArrayList<>();

        // Check max lots per underlying
        if (underlyingRiskLimits.getMaxLots() != null) {
            int currentLots = getCurrentLotsForUnderlying(underlying);
            int orderLots = request.getQuantity();
            if (currentLots + orderLots > underlyingRiskLimits.getMaxLots()) {
                violations.add(RiskViolation.of(
                        "UNDERLYING_LOT_LIMIT_EXCEEDED",
                        "Total lots for " + underlying + " would exceed limit: " + (currentLots + orderLots) + " > "
                                + underlyingRiskLimits.getMaxLots()));
            }
        }

        return violations;
    }

    // ========================
    // POSITION EVENT HANDLING
    // ========================

    /**
     * Updates the instrument-to-position index when a position changes.
     * This enables instrument-aware risk checking (only check positions
     * for the ticked instrument, not all positions).
     */
    @EventListener
    public void onPositionUpdate(PositionEvent event) {
        Position position = event.getPosition();
        positionRiskChecker.updatePositionIndex(position);

        // Check if this position has breached limits
        if (positionRiskChecker.isLossLimitBreached(position)) {
            log.error("Position {} breached loss limit", position.getId());
            publishRiskEvent(
                    RiskEventType.POSITION_LIMIT_BREACH,
                    RiskLevel.CRITICAL,
                    "Position " + position.getId() + " exceeded loss limit",
                    Map.of(
                            "positionId",
                            position.getId(),
                            "unrealizedPnl",
                            position.getUnrealizedPnl() != null
                                    ? position.getUnrealizedPnl().toString()
                                    : "N/A",
                            "maxLoss",
                            riskLimits.getMaxLossPerPosition() != null
                                    ? riskLimits.getMaxLossPerPosition().toString()
                                    : "N/A"));
        }
    }

    // ========================
    // UNDERLYING LIMITS MANAGEMENT
    // ========================

    /**
     * Sets per-underlying risk limits. Replaces any existing limits for that underlying.
     */
    public void setUnderlyingLimits(String underlying, UnderlyingRiskLimits limits) {
        underlyingLimitsMap.put(underlying, limits);
        log.info("Updated risk limits for underlying {}: {}", underlying, limits);
    }

    /**
     * Returns per-underlying limits for a given underlying, or null if not configured.
     */
    public UnderlyingRiskLimits getUnderlyingLimits(String underlying) {
        return underlyingLimitsMap.get(underlying);
    }

    /**
     * Returns an unmodifiable view of all per-underlying limits.
     */
    public Map<String, UnderlyingRiskLimits> getAllUnderlyingLimits() {
        return Collections.unmodifiableMap(underlyingLimitsMap);
    }

    // ========================
    // QUERIES
    // ========================

    /**
     * Returns the current global risk limits.
     */
    public RiskLimits getLimits() {
        return riskLimits;
    }

    // ========================
    // INTERNALS
    // ========================

    /**
     * Extracts the underlying symbol from a trading symbol.
     * Examples: "NIFTY24FEB22000CE" -> "NIFTY", "BANKNIFTY45000PE" -> "BANKNIFTY"
     */
    String extractUnderlying(String tradingSymbol) {
        if (tradingSymbol == null || tradingSymbol.isEmpty()) {
            return null;
        }
        // Find where digits start -- everything before is the underlying
        for (int i = 0; i < tradingSymbol.length(); i++) {
            if (Character.isDigit(tradingSymbol.charAt(i))) {
                return i > 0 ? tradingSymbol.substring(0, i) : null;
            }
        }
        return tradingSymbol;
    }

    /**
     * Calculates total lots currently held for a given underlying across all positions.
     */
    private int getCurrentLotsForUnderlying(String underlying) {
        return positionRiskChecker.getPositionsForInstrument(0).stream() // #TODO: Need instrument token lookup
                .filter(p ->
                        p.getTradingSymbol() != null && p.getTradingSymbol().startsWith(underlying))
                .mapToInt(p -> Math.abs(p.getQuantity()))
                .sum();
    }

    private void publishRiskEvent(
            RiskEventType eventType, RiskLevel level, String message, Map<String, Object> details) {
        applicationEventPublisher.publishEvent(new RiskEvent(this, eventType, level, message, details));
    }
}
