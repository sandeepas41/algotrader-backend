package com.algotrader.risk;

import com.algotrader.domain.model.Position;
import com.algotrader.oms.OrderRequest;
import com.algotrader.repository.redis.PositionRedisRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates orders and monitors positions against position-level risk limits.
 *
 * <p>Responsible for:
 * <ul>
 *   <li><b>Pre-trade validation:</b> Checks if an order would exceed max lots or max
 *       notional value per position before allowing it through the OMS</li>
 *   <li><b>Real-time monitoring:</b> Checks all positions for loss/profit limit breaches
 *       on every tick (via RiskManager)</li>
 *   <li><b>Instrument-aware checking:</b> Maintains an instrument-token-to-positions index
 *       for efficient per-instrument risk evaluation (avoids scanning all positions)</li>
 * </ul>
 *
 * <p>The instrument index is updated by RiskManager when PositionEvents arrive.
 * On tick evaluation, only positions matching the ticked instrument are checked.
 */
@Component
public class PositionRiskChecker {

    private static final Logger log = LoggerFactory.getLogger(PositionRiskChecker.class);

    private final RiskLimits riskLimits;
    private final PositionRedisRepository positionRedisRepository;

    /**
     * Instrument-aware position index: maps instrumentToken -> list of positions.
     * Updated on PositionEvent, queried on tick for efficient per-instrument checking.
     */
    private final Map<Long, List<Position>> positionsByInstrument = new ConcurrentHashMap<>();

    public PositionRiskChecker(RiskLimits riskLimits, PositionRedisRepository positionRedisRepository) {
        this.riskLimits = riskLimits;
        this.positionRedisRepository = positionRedisRepository;
    }

    // ========================
    // PRE-TRADE VALIDATION
    // ========================

    /**
     * Validates an order against position-level risk limits.
     *
     * @param request the order to validate
     * @return list of violations (empty if order is within limits)
     */
    public List<RiskViolation> validateOrder(OrderRequest request) {
        List<RiskViolation> violations = new ArrayList<>();

        // Check position size (lots) limit
        if (riskLimits.getMaxLotsPerPosition() != null) {
            int maxQuantity = riskLimits.getMaxLotsPerPosition();
            if (request.getQuantity() > maxQuantity) {
                violations.add(RiskViolation.of(
                        "POSITION_SIZE_EXCEEDED",
                        "Order quantity " + request.getQuantity() + " exceeds max lots per position: " + maxQuantity));
            }
        }

        // Check position notional value limit
        if (riskLimits.getMaxPositionValue() != null && request.getPrice() != null) {
            BigDecimal orderValue = request.getPrice().multiply(BigDecimal.valueOf(request.getQuantity()));
            if (orderValue.compareTo(riskLimits.getMaxPositionValue()) > 0) {
                violations.add(RiskViolation.of(
                        "POSITION_VALUE_EXCEEDED",
                        "Order value " + orderValue + " exceeds max position value: "
                                + riskLimits.getMaxPositionValue()));
            }
        }

        return violations;
    }

    // ========================
    // REAL-TIME MONITORING
    // ========================

    /**
     * Checks positions for the given instrument token against loss/profit limits.
     * Called by RiskManager on tick events for instrument-aware checking.
     *
     * @param instrumentToken the ticked instrument
     * @return list of positions that have breached their limits
     */
    public List<Position> checkPositionsForInstrument(long instrumentToken) {
        List<Position> positions = positionsByInstrument.getOrDefault(instrumentToken, Collections.emptyList());
        List<Position> breached = new ArrayList<>();

        for (Position position : positions) {
            if (isLossLimitBreached(position)) {
                log.warn(
                        "Position {} breached loss limit: unrealizedPnl={}",
                        position.getId(),
                        position.getUnrealizedPnl());
                breached.add(position);
            }
        }

        return breached;
    }

    /**
     * Checks ALL positions (full scan). Used for periodic health checks
     * or on startup, not on every tick.
     *
     * @return list of positions that have breached their loss limits
     */
    public List<Position> checkAllPositions() {
        List<Position> allPositions = positionRedisRepository.findAll();
        List<Position> breached = new ArrayList<>();

        for (Position position : allPositions) {
            if (isLossLimitBreached(position)) {
                log.warn(
                        "Position {} breached loss limit: unrealizedPnl={}",
                        position.getId(),
                        position.getUnrealizedPnl());
                breached.add(position);
            }
        }

        return breached;
    }

    /**
     * Returns true if the position's unrealized P&L has exceeded the max loss limit.
     * A position is in breach when its unrealizedPnl <= -(maxLossPerPosition).
     */
    public boolean isLossLimitBreached(Position position) {
        if (riskLimits.getMaxLossPerPosition() == null) {
            return false;
        }
        BigDecimal pnl = position.getUnrealizedPnl();
        if (pnl == null) {
            return false;
        }
        // maxLossPerPosition is a positive number; breach when pnl <= -maxLoss
        return pnl.compareTo(riskLimits.getMaxLossPerPosition().negate()) <= 0;
    }

    /**
     * Returns true if the position has reached the optional profit target.
     * Null maxProfitPerPosition means this check is disabled.
     */
    public boolean isProfitTargetReached(Position position) {
        if (riskLimits.getMaxProfitPerPosition() == null) {
            return false;
        }
        BigDecimal pnl = position.getUnrealizedPnl();
        if (pnl == null) {
            return false;
        }
        return pnl.compareTo(riskLimits.getMaxProfitPerPosition()) >= 0;
    }

    // ========================
    // INSTRUMENT INDEX
    // ========================

    /**
     * Updates the instrument-to-position index when a position is created/updated.
     * Called by RiskManager on PositionEvent.
     */
    public void updatePositionIndex(Position position) {
        if (position.getInstrumentToken() == null) {
            return;
        }
        long token = position.getInstrumentToken();

        positionsByInstrument.compute(token, (key, existing) -> {
            List<Position> positions = existing != null ? new ArrayList<>(existing) : new ArrayList<>();
            // Replace existing or add new
            positions.removeIf(p -> p.getId().equals(position.getId()));

            // Only add if position is still open (quantity != 0)
            if (position.getQuantity() != 0) {
                positions.add(position);
            }

            return positions.isEmpty() ? null : positions;
        });
    }

    /**
     * Removes a position from the instrument index.
     * Called when a position is fully closed.
     */
    public void removeFromIndex(String positionId) {
        positionsByInstrument.forEach((token, positions) -> {
            positions.removeIf(p -> p.getId().equals(positionId));
        });
        // Clean up empty lists
        positionsByInstrument.values().removeIf(List::isEmpty);
    }

    /**
     * Returns the current positions for a given instrument token.
     * Used for instrument-aware risk checking (only check relevant positions on tick).
     */
    public List<Position> getPositionsForInstrument(long instrumentToken) {
        return positionsByInstrument.getOrDefault(instrumentToken, Collections.emptyList());
    }

    /**
     * Returns the total number of indexed positions across all instruments.
     */
    public int getIndexedPositionCount() {
        return positionsByInstrument.values().stream().mapToInt(List::size).sum();
    }
}
