package com.algotrader.strategy.adoption;

import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Instrument;
import com.algotrader.domain.model.Position;
import com.algotrader.entity.StrategyLegEntity;
import com.algotrader.exception.BusinessException;
import com.algotrader.exception.ErrorCode;
import com.algotrader.exception.ResourceNotFoundException;
import com.algotrader.repository.jpa.StrategyLegJpaRepository;
import com.algotrader.repository.redis.PositionRedisRepository;
import com.algotrader.service.InstrumentService;
import com.algotrader.strategy.base.BaseStrategy;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Manages position adoption (attach) and detach operations between positions and strategies.
 *
 * <p>With the redesigned position-strategy linking model, positions are strategy-unaware.
 * Adoption creates a StrategyLeg linked to the position with a specified quantity.
 * Detach clears the leg's positionId, releasing the allocated quantity.
 *
 * <p>The same position can be adopted into multiple strategies, each with its own
 * allocated quantity. Validation ensures the total allocated quantity across all
 * strategies does not exceed the position's broker quantity.
 *
 * <p><b>Adopt flow:</b>
 * <ol>
 *   <li>Validate position exists in Redis</li>
 *   <li>Validate quantity sign matches and does not exceed unmanaged remainder</li>
 *   <li>Validate strategy is not already linked to this position</li>
 *   <li>Validate compatibility (underlying, option type)</li>
 *   <li>Create a StrategyLeg with positionId + quantity</li>
 *   <li>Add position to strategy's in-memory list</li>
 *   <li>Recalculate entry premium</li>
 * </ol>
 *
 * <p><b>Detach flow:</b>
 * <ol>
 *   <li>Find the leg linked to the position in this strategy</li>
 *   <li>Clear the leg's positionId</li>
 *   <li>Remove position from strategy's in-memory list</li>
 *   <li>Recalculate entry premium</li>
 * </ol>
 */
@Service
public class PositionAdoptionService {

    private static final Logger log = LoggerFactory.getLogger(PositionAdoptionService.class);

    private static final Set<StrategyType> CE_ONLY_STRATEGIES = Set.of(StrategyType.BULL_CALL_SPREAD);
    private static final Set<StrategyType> PE_ONLY_STRATEGIES =
            Set.of(StrategyType.BULL_PUT_SPREAD, StrategyType.BEAR_PUT_SPREAD);

    private final PositionRedisRepository positionRedisRepository;
    private final StrategyLegJpaRepository strategyLegJpaRepository;
    private final PositionAllocationService positionAllocationService;
    private final StrategyEngine strategyEngine;
    private final InstrumentService instrumentService;

    public PositionAdoptionService(
            PositionRedisRepository positionRedisRepository,
            StrategyLegJpaRepository strategyLegJpaRepository,
            PositionAllocationService positionAllocationService,
            StrategyEngine strategyEngine,
            InstrumentService instrumentService) {
        this.positionRedisRepository = positionRedisRepository;
        this.strategyLegJpaRepository = strategyLegJpaRepository;
        this.positionAllocationService = positionAllocationService;
        this.strategyEngine = strategyEngine;
        this.instrumentService = instrumentService;
    }

    // ========================
    // STARTUP
    // ========================

    /**
     * Cleans up orphaned strategy legs (legs whose strategyId has no corresponding
     * in-memory strategy) and populates the reverse index for surviving legs.
     *
     * <p>Orphaned legs occur when the server restarts and strategies from a previous
     * session are no longer running. Without cleanup, the allocation service would
     * incorrectly mark positions as "managed" by non-existent strategies.
     *
     * <p>Called by {@link com.algotrader.recovery.StartupRecoveryService} AFTER strategies
     * are restored from H2, ensuring the in-memory strategy map is populated first.
     */
    public void populatePositionIndexOnStartup() {
        // Clean up orphaned legs first
        Set<String> activeStrategyIds = strategyEngine.getActiveStrategies().keySet();
        List<StrategyLegEntity> allLegs = strategyLegJpaRepository.findAll();
        List<StrategyLegEntity> orphanedLegs = allLegs.stream()
                .filter(leg -> !activeStrategyIds.contains(leg.getStrategyId()))
                .toList();

        if (!orphanedLegs.isEmpty()) {
            strategyLegJpaRepository.deleteAll(orphanedLegs);
            log.info(
                    "Cleaned up {} orphaned strategy legs from {} defunct strategies",
                    orphanedLegs.size(),
                    orphanedLegs.stream()
                            .map(StrategyLegEntity::getStrategyId)
                            .distinct()
                            .count());
        }

        // Populate reverse index from surviving legs
        List<StrategyLegEntity> linkedLegs = strategyLegJpaRepository.findByPositionIdIsNotNull();
        List<Map.Entry<String, String>> links = linkedLegs.stream()
                .<Map.Entry<String, String>>map(leg -> Map.entry(leg.getPositionId(), leg.getStrategyId()))
                .toList();
        strategyEngine.populatePositionIndex(links);
    }

    // ========================
    // ADOPT
    // ========================

    /**
     * Adopts a position into a strategy by creating a new StrategyLeg linked to it.
     *
     * @param strategy   the target strategy
     * @param positionId the position to adopt
     * @param quantity   signed quantity to allocate (must match position sign)
     * @return result with any warnings
     */
    public AdoptionResult adoptPosition(BaseStrategy strategy, String positionId, int quantity) {
        Position position = getPositionOrThrow(positionId);
        String strategyId = strategy.getId();
        List<String> warnings = new ArrayList<>();

        // Validate: quantity is non-zero
        if (quantity == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Quantity must not be zero");
        }

        // Validate: quantity sign matches position sign
        if ((quantity > 0 && position.getQuantity() < 0) || (quantity < 0 && position.getQuantity() > 0)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Quantity sign (" + quantity + ") does not match position sign (" + position.getQuantity() + ")");
        }

        // Validate: quantity does not exceed unmanaged remainder
        int unmanaged = positionAllocationService.getUnmanagedQuantity(positionId, position.getQuantity());
        if (Math.abs(quantity) > unmanaged) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Requested quantity (" + Math.abs(quantity) + ") exceeds unmanaged remainder (" + unmanaged + ")");
        }

        // Validate: strategy does not already have a leg linked to this position
        List<StrategyLegEntity> existingLegs = strategyLegJpaRepository.findByStrategyId(strategyId);
        boolean alreadyLinked = existingLegs.stream().anyMatch(leg -> positionId.equals(leg.getPositionId()));
        if (alreadyLinked) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Position " + positionId + " is already linked to strategy " + strategyId);
        }

        // Validate: underlying and option type compatibility
        validateUnderlying(position, strategy, warnings);
        validateOptionTypeCompatibility(position, strategy, warnings);

        // Look up instrument from cache for accurate strike and option type
        Instrument instrument = instrumentService
                .findByToken(position.getInstrumentToken())
                .orElseThrow(() ->
                        new ResourceNotFoundException("Instrument", String.valueOf(position.getInstrumentToken())));
        InstrumentType optionType = instrument.getType();
        BigDecimal strike = instrument.getStrike();

        // Create StrategyLeg
        StrategyLegEntity legEntity = StrategyLegEntity.builder()
                .id(UUID.randomUUID().toString())
                .strategyId(strategyId)
                .optionType(optionType)
                .strike(strike)
                .quantity(quantity)
                .positionId(positionId)
                .build();
        strategyLegJpaRepository.save(legEntity);

        // Add position to strategy's in-memory list (if not already present)
        boolean alreadyInMemory = strategy.getPositions().stream().anyMatch(p -> positionId.equals(p.getId()));
        if (!alreadyInMemory) {
            strategy.addPosition(position);
        }

        // Update reverse index for tick routing
        strategyEngine.registerPositionLink(positionId, strategyId);

        // Recalculate entry premium
        BigDecimal entryPremium = recalculateEntryPremium(strategy);

        log.info(
                "[{}] Position adopted: positionId={}, symbol={}, qty={}, legId={}, entryPremium={}",
                strategyId,
                positionId,
                position.getTradingSymbol(),
                quantity,
                legEntity.getId(),
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
     * Detaches a position from a strategy by clearing the leg's positionId.
     * The leg itself is preserved (with null positionId) — only the link is severed.
     *
     * @param strategy   the strategy to detach from
     * @param positionId the position to detach
     * @return result with recalculated entry premium
     */
    public AdoptionResult detachPosition(BaseStrategy strategy, String positionId) {
        String strategyId = strategy.getId();

        // Find the leg linked to this position in this strategy
        List<StrategyLegEntity> legs = strategyLegJpaRepository.findByStrategyId(strategyId);
        StrategyLegEntity linkedLeg = legs.stream()
                .filter(leg -> positionId.equals(leg.getPositionId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "StrategyLeg", "position " + positionId + " in strategy " + strategyId));

        // Clear the positionId on the leg
        linkedLeg.setPositionId(null);
        strategyLegJpaRepository.save(linkedLeg);

        // Remove position from strategy's in-memory list (if no other legs reference it)
        boolean otherLegsReferencePosition = legs.stream()
                .filter(leg -> !leg.getId().equals(linkedLeg.getId()))
                .anyMatch(leg -> positionId.equals(leg.getPositionId()));
        if (!otherLegsReferencePosition) {
            strategy.removePosition(positionId);
            // Update reverse index — only remove link if no other legs reference this position
            strategyEngine.unregisterPositionLink(positionId, strategyId);
        }

        // Recalculate entry premium
        BigDecimal entryPremium = recalculateEntryPremium(strategy);

        log.info(
                "[{}] Position detached: positionId={}, legId={}, remainingPositions={}, entryPremium={}",
                strategyId,
                positionId,
                linkedLeg.getId(),
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
    // VALIDATION
    // ========================

    /**
     * Validates that the position's underlying matches the strategy's underlying.
     * Trading symbol format: NIFTY22000CE → underlying is "NIFTY".
     */
    void validateUnderlying(Position position, BaseStrategy strategy, List<String> warnings) {
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
     */
    void validateOptionTypeCompatibility(Position position, BaseStrategy strategy, List<String> warnings) {
        String symbol = position.getTradingSymbol();
        StrategyType strategyType = strategy.getType();

        if (symbol == null) {
            return;
        }

        boolean isCE = symbol.endsWith("CE");
        boolean isPE = symbol.endsWith("PE");

        if (!isCE && !isPE) {
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

    // ========================
    // ENTRY PREMIUM RECALCULATION
    // ========================

    /**
     * Recalculates entry premium for a strategy based on all current positions' average prices.
     * Entry premium = weighted average of |averagePrice| across positions, weighted by |quantity|.
     */
    BigDecimal recalculateEntryPremium(BaseStrategy strategy) {
        List<Position> positions = strategy.getPositions();

        if (positions.isEmpty()) {
            strategy.setEntryPremium(null);
            return null;
        }

        BigDecimal totalPremium = positions.stream()
                .filter(p -> p.getAveragePrice() != null)
                .map(p -> p.getAveragePrice().multiply(BigDecimal.valueOf(Math.abs(p.getQuantity()))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

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
