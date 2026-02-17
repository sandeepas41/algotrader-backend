/**
 * Service for individual leg operations on live strategies: close a single leg
 * or roll it to a different strike. Works with both MARKET and LIMIT orders.
 *
 * Key design decisions:
 * - Order execution happens outside strategy locks (JournaledMultiLegExecutor handles WAL).
 * - Post-execution state updates (removePosition, clear positionId) are safe because
 *   BaseStrategy.removePosition() uses its own StampedLock internally.
 * - Position reverse index is updated via StrategyEngine.unregisterPositionLink().
 */
package com.algotrader.strategy;

import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Instrument;
import com.algotrader.domain.model.Position;
import com.algotrader.entity.StrategyLegEntity;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.exception.BusinessException;
import com.algotrader.exception.ErrorCode;
import com.algotrader.exception.ResourceNotFoundException;
import com.algotrader.oms.JournaledMultiLegExecutor;
import com.algotrader.oms.OrderRequest;
import com.algotrader.repository.jpa.StrategyLegJpaRepository;
import com.algotrader.service.InstrumentService;
import com.algotrader.strategy.base.BaseStrategy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class LegOperationService {

    private final StrategyEngine strategyEngine;
    private final StrategyLegJpaRepository strategyLegJpaRepository;
    private final JournaledMultiLegExecutor journaledMultiLegExecutor;
    private final InstrumentService instrumentService;
    private final EventPublisherHelper eventPublisherHelper;

    /**
     * Close a single leg by placing an exit order for its linked position.
     *
     * @param strategyId the strategy owning the leg
     * @param legId      the leg to close
     * @param orderType  MARKET or LIMIT
     * @param price      limit price (required for LIMIT, ignored for MARKET)
     * @return the execution group ID for tracking
     */
    public String closeLeg(String strategyId, String legId, OrderType orderType, BigDecimal price) {
        BaseStrategy strategy = strategyEngine.getStrategy(strategyId);
        if (strategy == null) {
            throw new ResourceNotFoundException("Strategy", strategyId);
        }

        // Find leg entity in H2
        StrategyLegEntity legEntity =
                strategyLegJpaRepository.findById(legId).orElseThrow(() -> new ResourceNotFoundException("Leg", legId));

        if (!legEntity.getStrategyId().equals(strategyId)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Leg " + legId + " does not belong to strategy " + strategyId);
        }

        String positionId = legEntity.getPositionId();
        if (positionId == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Leg " + legId + " has no linked position — already closed or never entered");
        }

        // Find position from strategy's in-memory list
        Position position = strategy.getPositions().stream()
                .filter(p -> p.getId().equals(positionId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Position " + positionId + " not found in strategy's active positions"));

        // Validate LIMIT price
        if (orderType == OrderType.LIMIT && price == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Price is required for LIMIT orders");
        }

        // Build exit order: opposite side of current position
        OrderRequest exitOrder = buildExitOrder(position, strategyId, orderType, price);

        log.info(
                "Closing leg {} (position {}) for strategy {} with {} order", legId, positionId, strategyId, orderType);

        // Execute via WAL-based executor
        JournaledMultiLegExecutor.MultiLegResult result = journaledMultiLegExecutor.executeSequential(
                List.of(exitOrder), strategyId, "CLOSE_LEG", OrderPriority.MANUAL);

        if (!result.isSuccess()) {
            String reason = result.getLegResults().isEmpty()
                    ? "Unknown execution failure"
                    : result.getLegResults().get(0).getFailureReason();
            log.error("Failed to close leg {} for strategy {}: {}", legId, strategyId, reason);
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Order execution failed: " + reason);
        }

        // Success — update in-memory and persistent state
        postCloseLeg(strategy, strategyId, legEntity, positionId, result.getGroupId());

        return result.getGroupId();
    }

    /**
     * Roll a leg to a different strike: close current position, open new one.
     * Executed sequentially (close first, then open) with rollback on failure.
     *
     * @param strategyId     the strategy owning the leg
     * @param legId          the leg to roll
     * @param newStrike      target strike price
     * @param closeOrderType MARKET or LIMIT for the close order
     * @param closePrice     limit price for close (if LIMIT)
     * @param openOrderType  MARKET or LIMIT for the open order
     * @param openPrice      limit price for open (if LIMIT)
     * @return the execution group ID
     */
    public String rollLeg(
            String strategyId,
            String legId,
            BigDecimal newStrike,
            OrderType closeOrderType,
            BigDecimal closePrice,
            OrderType openOrderType,
            BigDecimal openPrice) {
        BaseStrategy strategy = strategyEngine.getStrategy(strategyId);
        if (strategy == null) {
            throw new ResourceNotFoundException("Strategy", strategyId);
        }

        StrategyLegEntity legEntity =
                strategyLegJpaRepository.findById(legId).orElseThrow(() -> new ResourceNotFoundException("Leg", legId));

        if (!legEntity.getStrategyId().equals(strategyId)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Leg " + legId + " does not belong to strategy " + strategyId);
        }

        String positionId = legEntity.getPositionId();
        if (positionId == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Leg " + legId + " has no linked position — cannot roll");
        }

        Position position = strategy.getPositions().stream()
                .filter(p -> p.getId().equals(positionId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Position " + positionId + " not found in strategy's active positions"));

        // Validate LIMIT prices
        if (closeOrderType == OrderType.LIMIT && closePrice == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Close price is required for LIMIT close orders");
        }
        if (openOrderType == OrderType.LIMIT && openPrice == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Open price is required for LIMIT open orders");
        }

        // Resolve new instrument at the target strike
        InstrumentType optionType = legEntity.getOptionType();
        LocalDate expiry = strategy.getConfig().getExpiry();
        String underlying = strategy.getUnderlying();

        Optional<Instrument> newInstrumentOpt =
                instrumentService.resolveOption(underlying, expiry, newStrike, optionType);
        if (newInstrumentOpt.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "No instrument found for " + underlying + " " + expiry + " " + newStrike + " " + optionType);
        }
        Instrument newInstrument = newInstrumentOpt.get();

        // Build close order (opposite side) + open order (same side as original leg)
        OrderRequest closeOrder = buildExitOrder(position, strategyId, closeOrderType, closePrice);
        OrderSide openSide = legEntity.getQuantity() > 0 ? OrderSide.BUY : OrderSide.SELL;
        // Use same share count as the position being closed
        int openQuantity = Math.abs(position.getQuantity());
        OrderRequest openOrder = OrderRequest.builder()
                .instrumentToken(newInstrument.getToken())
                .tradingSymbol(newInstrument.getTradingSymbol())
                .exchange(newInstrument.getExchange())
                .side(openSide)
                .type(openOrderType != null ? openOrderType : OrderType.MARKET)
                .quantity(openQuantity)
                .price(openPrice)
                .strategyId(strategyId)
                .correlationId("ROLL_LEG_OPEN_" + legId)
                .build();

        log.info(
                "Rolling leg {} from strike {} to {} for strategy {}",
                legId,
                legEntity.getStrike(),
                newStrike,
                strategyId);

        // Execute sequentially: close first, then open. Rollback on failure.
        JournaledMultiLegExecutor.MultiLegResult result = journaledMultiLegExecutor.executeSequential(
                List.of(closeOrder, openOrder), strategyId, "ROLL_LEG", OrderPriority.MANUAL);

        if (!result.isSuccess()) {
            String reason = result.getLegResults().stream()
                    .filter(r -> !r.isSuccess())
                    .map(JournaledMultiLegExecutor.LegResult::getFailureReason)
                    .findFirst()
                    .orElse("Unknown execution failure");
            log.error("Failed to roll leg {} for strategy {}: {}", legId, strategyId, reason);
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Roll execution failed: " + reason);
        }

        // Success — detach old position, update leg entity with new strike
        postCloseLeg(strategy, strategyId, legEntity, positionId, result.getGroupId());

        // Update leg entity with new strike
        legEntity.setStrike(newStrike);
        // positionId will be linked when the new position is reconciled by StrategyEngine
        strategyLegJpaRepository.save(legEntity);

        eventPublisherHelper.publishDecision(
                this,
                "LEG_ROLL",
                "Rolled leg " + legId + " from strike " + position.getTradingSymbol() + " to " + newStrike,
                strategyId,
                Map.of("legId", legId, "newStrike", newStrike, "groupId", result.getGroupId()));

        log.info("Successfully rolled leg {} to strike {} (group {})", legId, newStrike, result.getGroupId());
        return result.getGroupId();
    }

    /** Build an exit order for a position (opposite side, specified order type). */
    private OrderRequest buildExitOrder(Position position, String strategyId, OrderType orderType, BigDecimal price) {
        OrderSide exitSide = position.getQuantity() > 0 ? OrderSide.SELL : OrderSide.BUY;
        return OrderRequest.builder()
                .instrumentToken(position.getInstrumentToken())
                .tradingSymbol(position.getTradingSymbol())
                .exchange(position.getExchange())
                .side(exitSide)
                .type(orderType != null ? orderType : OrderType.MARKET)
                .quantity(Math.abs(position.getQuantity()))
                .price(price)
                .strategyId(strategyId)
                .correlationId("CLOSE_LEG_" + position.getId())
                .build();
    }

    /** Post-close cleanup: remove position from strategy, clear leg link, update reverse index. */
    private void postCloseLeg(
            BaseStrategy strategy, String strategyId, StrategyLegEntity legEntity, String positionId, String groupId) {
        // Remove position from strategy's in-memory list (thread-safe via StampedLock)
        strategy.removePosition(positionId);

        // Clear the position link on the leg entity
        legEntity.setPositionId(null);
        strategyLegJpaRepository.save(legEntity);

        // Update reverse index
        strategyEngine.unregisterPositionLink(positionId, strategyId);

        eventPublisherHelper.publishDecision(
                this,
                "LEG_CLOSE",
                "Closed leg " + legEntity.getId() + " (position " + positionId + ")",
                strategyId,
                Map.of("legId", legEntity.getId(), "positionId", positionId, "groupId", groupId));

        log.info("Leg {} closed successfully (position {}, group {})", legEntity.getId(), positionId, groupId);
    }
}
