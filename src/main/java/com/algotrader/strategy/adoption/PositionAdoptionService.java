package com.algotrader.strategy.adoption;

import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Position;
import com.algotrader.exception.BusinessException;
import com.algotrader.exception.ErrorCode;
import com.algotrader.exception.ResourceNotFoundException;
import com.algotrader.repository.redis.PositionRedisRepository;
import com.algotrader.strategy.base.BaseStrategy;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Manages position adoption (attach) and detach operations between positions and strategies.
 *
 * <p>Useful when:
 * <ul>
 *   <li>A trade was entered manually in Kite and needs platform management</li>
 *   <li>A strategy crashed and positions need re-association</li>
 *   <li>Monitoring existing positions with strategy-level P&L</li>
 * </ul>
 *
 * <p><b>Adopt flow:</b>
 * <ol>
 *   <li>Validate position exists and isn't already assigned</li>
 *   <li>Validate position is compatible with the strategy type (option type, underlying)</li>
 *   <li>Check for quantity mismatch with strategy lot size</li>
 *   <li>Assign position to strategy (Redis + in-memory)</li>
 *   <li>Recalculate entry premium from all strategy positions' average prices</li>
 *   <li>Return result with any warnings</li>
 * </ol>
 *
 * <p><b>Detach flow:</b>
 * <ol>
 *   <li>Validate position belongs to the specified strategy</li>
 *   <li>Remove strategy assignment (Redis + in-memory)</li>
 *   <li>Recalculate entry premium for remaining positions</li>
 * </ol>
 *
 * <p><b>Orphan detection:</b> Finds positions in Redis that have no strategy assignment.
 * Called on startup or periodically to surface untracked positions.
 *
 * <p>Uses PositionRedisRepository directly (PositionService doesn't exist yet).
 * Logging goes through SLF4J (DecisionLogger deferred to Task 8.1).
 */
@Service
public class PositionAdoptionService {

    private static final Logger log = LoggerFactory.getLogger(PositionAdoptionService.class);

    /**
     * Strategy types that require CE (Call) positions.
     * Bull Call Spread only uses CE legs.
     */
    private static final Set<StrategyType> CE_ONLY_STRATEGIES = Set.of(StrategyType.BULL_CALL_SPREAD);

    /**
     * Strategy types that require PE (Put) positions.
     * Bull Put Spread only uses PE legs.
     */
    private static final Set<StrategyType> PE_ONLY_STRATEGIES =
            Set.of(StrategyType.BULL_PUT_SPREAD, StrategyType.BEAR_PUT_SPREAD);

    /**
     * Strategy types that use both CE and PE positions.
     * Straddle, Strangle, Iron Condor, Iron Butterfly all use both sides.
     */
    private static final Set<StrategyType> DUAL_OPTION_STRATEGIES =
            Set.of(StrategyType.STRADDLE, StrategyType.STRANGLE, StrategyType.IRON_CONDOR, StrategyType.IRON_BUTTERFLY);

    private final PositionRedisRepository positionRedisRepository;

    // #TODO Task 8.1: Replace with DecisionLogger for structured decision logging
    // #TODO Task 6.2 follow-up: Inject StrategyEngine once circular dependency is resolved
    //   For now, the caller (StrategyEngine or API controller) passes the BaseStrategy directly.

    public PositionAdoptionService(PositionRedisRepository positionRedisRepository) {
        this.positionRedisRepository = positionRedisRepository;
    }

    // ========================
    // ADOPT
    // ========================

    /**
     * Attaches an existing position to a strategy for tracking and management.
     *
     * <p>The position must exist in Redis and must not be assigned to another strategy.
     * Validates compatibility between the position's option type and the strategy type.
     * Recalculates entry premium after adoption based on all positions' average prices.
     *
     * @param strategy   the strategy to adopt the position into
     * @param positionId the position to adopt
     * @return result with recalculated entry premium and any warnings
     * @throws ResourceNotFoundException if position doesn't exist
     * @throws BusinessException         if position is already assigned to another strategy
     */
    public AdoptionResult adoptPosition(BaseStrategy strategy, String positionId) {
        Position position = getPositionOrThrow(positionId);
        String strategyId = strategy.getId();
        List<String> warnings = new ArrayList<>();

        // Validate: position not already assigned to a different strategy
        if (position.getStrategyId() != null && !position.getStrategyId().equals(strategyId)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Position " + positionId + " already belongs to strategy " + position.getStrategyId());
        }

        // Validate: position not already in this strategy
        if (strategyId.equals(position.getStrategyId())) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Position " + positionId + " is already assigned to strategy " + strategyId);
        }

        // Validate: underlying matches
        validateUnderlying(position, strategy, warnings);

        // Validate: option type compatible with strategy type
        validateOptionTypeCompatibility(position, strategy, warnings);

        // Warn: quantity mismatch with strategy lot size
        checkQuantityMismatch(position, strategy, warnings);

        // Assign position to strategy in Redis
        position.setStrategyId(strategyId);
        positionRedisRepository.save(position);

        // Add position to strategy's in-memory list
        strategy.addPosition(position);

        // Recalculate entry premium from all positions
        BigDecimal entryPremium = recalculateEntryPremium(strategy);

        log.info(
                "[{}] Position adopted: positionId={}, symbol={}, qty={}, entryPremium={}",
                strategyId,
                positionId,
                position.getTradingSymbol(),
                position.getQuantity(),
                entryPremium);

        if (!warnings.isEmpty()) {
            log.warn("[{}] Adoption warnings for position {}: {}", strategyId, positionId, warnings);
        }

        return AdoptionResult.builder()
                .strategyId(strategyId)
                .positionId(positionId)
                .operationType(AdoptionResult.OperationType.ADOPT)
                .recalculatedEntryPremium(entryPremium)
                .warnings(warnings)
                .build();
    }

    // ========================
    // DETACH
    // ========================

    /**
     * Detaches a position from a strategy WITHOUT closing it.
     * The position remains open in the broker but is no longer tracked by the strategy.
     *
     * @param strategy   the strategy to detach the position from
     * @param positionId the position to detach
     * @return result with recalculated entry premium for remaining positions
     * @throws ResourceNotFoundException if position doesn't exist
     * @throws BusinessException         if position doesn't belong to this strategy
     */
    public AdoptionResult detachPosition(BaseStrategy strategy, String positionId) {
        Position position = getPositionOrThrow(positionId);
        String strategyId = strategy.getId();

        // Validate: position must belong to this strategy
        if (!strategyId.equals(position.getStrategyId())) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Position " + positionId + " does not belong to strategy " + strategyId);
        }

        // Remove strategy assignment in Redis
        position.setStrategyId(null);
        positionRedisRepository.save(position);

        // Remove from strategy's in-memory list
        strategy.removePosition(positionId);

        // Recalculate entry premium for remaining positions
        BigDecimal entryPremium = recalculateEntryPremium(strategy);

        log.info(
                "[{}] Position detached: positionId={}, symbol={}, remainingPositions={}, entryPremium={}",
                strategyId,
                positionId,
                position.getTradingSymbol(),
                strategy.getPositions().size(),
                entryPremium);

        return AdoptionResult.builder()
                .strategyId(strategyId)
                .positionId(positionId)
                .operationType(AdoptionResult.OperationType.DETACH)
                .recalculatedEntryPremium(entryPremium)
                .warnings(List.of())
                .build();
    }

    // ========================
    // ORPHAN DETECTION
    // ========================

    /**
     * Finds all positions that are not assigned to any strategy.
     * These are positions opened manually in Kite or whose strategy has been undeployed.
     *
     * <p>Called on startup sync or periodically to surface untracked positions
     * in the UI for the trader to review and potentially adopt.
     *
     * @return list of orphan positions (strategyId is null)
     */
    public List<Position> findOrphanPositions() {
        List<Position> allPositions = positionRedisRepository.findAll();
        List<Position> orphans =
                allPositions.stream().filter(p -> p.getStrategyId() == null).toList();

        if (!orphans.isEmpty()) {
            log.info("Found {} orphan positions (not assigned to any strategy)", orphans.size());
        }

        return orphans;
    }

    /**
     * Finds positions assigned to a strategy that is no longer active.
     * This can happen when a strategy crashes or is undeployed without closing positions.
     *
     * @param activeStrategyIds the set of currently active strategy IDs
     * @return list of positions whose strategyId doesn't match any active strategy
     */
    public List<Position> findStaleAssignments(Set<String> activeStrategyIds) {
        List<Position> allPositions = positionRedisRepository.findAll();
        List<Position> stale = allPositions.stream()
                .filter(p -> p.getStrategyId() != null && !activeStrategyIds.contains(p.getStrategyId()))
                .toList();

        if (!stale.isEmpty()) {
            log.warn(
                    "Found {} positions assigned to non-active strategies: {}",
                    stale.size(),
                    stale.stream()
                            .map(p -> p.getId() + "->" + p.getStrategyId())
                            .toList());
        }

        return stale;
    }

    // ========================
    // VALIDATION
    // ========================

    /**
     * Validates that the position's underlying matches the strategy's underlying.
     * Trading symbol format: NIFTY22000CE -> underlying is "NIFTY".
     */
    private void validateUnderlying(Position position, BaseStrategy strategy, List<String> warnings) {
        String symbol = position.getTradingSymbol();
        String strategyUnderlying = strategy.getUnderlying();

        if (symbol == null || strategyUnderlying == null) {
            return;
        }

        if (!symbol.startsWith(strategyUnderlying)) {
            warnings.add("Position symbol '" + symbol + "' may not match strategy underlying '" + strategyUnderlying
                    + "'. Verify this is the correct position.");
        }
    }

    /**
     * Validates that the position's option type (CE/PE) is compatible with the strategy type.
     * For example, a Bull Call Spread should only have CE positions.
     */
    private void validateOptionTypeCompatibility(Position position, BaseStrategy strategy, List<String> warnings) {
        String symbol = position.getTradingSymbol();
        StrategyType strategyType = strategy.getType();

        if (symbol == null) {
            return;
        }

        boolean isCE = symbol.endsWith("CE");
        boolean isPE = symbol.endsWith("PE");

        if (!isCE && !isPE) {
            // Not an option position (e.g., futures) -- no validation needed
            return;
        }

        if (isCE && PE_ONLY_STRATEGIES.contains(strategyType)) {
            warnings.add("CE position adopted into " + strategyType
                    + " strategy which typically uses PE positions only. Verify this is intentional.");
        }

        if (isPE && CE_ONLY_STRATEGIES.contains(strategyType)) {
            warnings.add("PE position adopted into " + strategyType
                    + " strategy which typically uses CE positions only. Verify this is intentional.");
        }
    }

    /**
     * Checks if the adopted position's quantity is consistent with the strategy's expected lot size.
     * The lot size from config represents the number of lots; actual quantity depends on the
     * instrument's lot size (e.g., NIFTY lot = 75 shares).
     *
     * <p>This is a warning, not a rejection -- manual positions might intentionally differ.
     */
    private void checkQuantityMismatch(Position position, BaseStrategy strategy, List<String> warnings) {
        int configuredLots = strategy.getPositions().isEmpty()
                ? 0
                : strategy.getPositions().stream()
                        .mapToInt(p -> Math.abs(p.getQuantity()))
                        .min()
                        .orElse(0);

        // If there are existing positions, compare quantities
        if (configuredLots > 0) {
            int positionQty = Math.abs(position.getQuantity());
            if (positionQty != configuredLots) {
                warnings.add("Position quantity (" + positionQty + ") differs from existing positions' quantity ("
                        + configuredLots + "). This may affect strategy P&L calculations.");
            }
        }
    }

    // ========================
    // ENTRY PREMIUM RECALCULATION
    // ========================

    /**
     * Recalculates entry premium for a strategy based on all current positions' average prices.
     *
     * <p>Entry premium = sum of (|averagePrice| * |quantity|) across all positions.
     * For a straddle selling both sides, this represents the total collected premium.
     * For directional strategies, it represents the net cost basis.
     *
     * <p>Updates the strategy's entryPremium field directly via the setter.
     *
     * @param strategy the strategy to recalculate for
     * @return the recalculated entry premium, or null if no positions
     */
    BigDecimal recalculateEntryPremium(BaseStrategy strategy) {
        List<Position> positions = strategy.getPositions();

        if (positions.isEmpty()) {
            strategy.setEntryPremium(null);
            return null;
        }

        // Entry premium = sum of (averagePrice * |quantity|) for all positions
        // This represents the total premium collected/paid when entering
        BigDecimal totalPremium = positions.stream()
                .filter(p -> p.getAveragePrice() != null)
                .map(p -> p.getAveragePrice().multiply(BigDecimal.valueOf(Math.abs(p.getQuantity()))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Normalize: per-lot premium (divide by total absolute quantity)
        int totalQuantity =
                positions.stream().mapToInt(p -> Math.abs(p.getQuantity())).sum();

        BigDecimal entryPremium;
        if (totalQuantity > 0) {
            entryPremium = totalPremium.divide(BigDecimal.valueOf(totalQuantity), MathContext.DECIMAL64);
        } else {
            entryPremium = BigDecimal.ZERO;
        }

        strategy.setEntryPremium(entryPremium);

        log.debug(
                "[{}] Entry premium recalculated: {} (from {} positions, totalQty={})",
                strategy.getId(),
                entryPremium,
                positions.size(),
                totalQuantity);

        return entryPremium;
    }

    // ========================
    // INTERNAL
    // ========================

    private Position getPositionOrThrow(String positionId) {
        return positionRedisRepository
                .findById(positionId)
                .orElseThrow(() -> new ResourceNotFoundException("Position", positionId));
    }
}
