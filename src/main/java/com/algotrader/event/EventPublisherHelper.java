package com.algotrader.event;

import com.algotrader.domain.enums.MarketPhase;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.model.AdjustmentAction;
import com.algotrader.domain.model.AdjustmentRule;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.ReconciliationResult;
import com.algotrader.domain.model.Strategy;
import com.algotrader.domain.model.Tick;
import com.algotrader.session.SessionState;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Convenience wrapper around Spring's {@link ApplicationEventPublisher} that provides
 * typed factory methods for all AlgoTrader event types.
 *
 * <p>Using this helper instead of direct {@code eventPublisher.publishEvent(new XxxEvent(...))}
 * provides several benefits:
 * <ul>
 *   <li>Single point of event creation — easy to add cross-cutting concerns (logging, metrics)</li>
 *   <li>Readable call sites: {@code eventPublisherHelper.publishTick(tick)} vs
 *       {@code eventPublisher.publishEvent(new TickEvent(this, tick))}</li>
 *   <li>Consistent source object handling (the helper itself is the source)</li>
 * </ul>
 *
 * <p>All methods are non-blocking. The actual event delivery depends on listener
 * annotations: synchronous {@code @EventListener} or async {@code @Async @EventListener}.
 */
@Component
public class EventPublisherHelper {

    private final ApplicationEventPublisher applicationEventPublisher;

    public EventPublisherHelper(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    // ---- Tick ----

    public void publishTick(Object source, Tick tick) {
        applicationEventPublisher.publishEvent(new TickEvent(source, tick));
    }

    // ---- Order ----

    public void publishOrderPlaced(Object source, Order order) {
        applicationEventPublisher.publishEvent(new OrderEvent(source, order, OrderEventType.PLACED));
    }

    public void publishOrderFilled(Object source, Order order, OrderStatus previousStatus) {
        applicationEventPublisher.publishEvent(new OrderEvent(source, order, OrderEventType.FILLED, previousStatus));
    }

    public void publishOrderCancelled(Object source, Order order, OrderStatus previousStatus) {
        applicationEventPublisher.publishEvent(new OrderEvent(source, order, OrderEventType.CANCELLED, previousStatus));
    }

    public void publishOrderRejected(Object source, Order order) {
        applicationEventPublisher.publishEvent(new OrderEvent(source, order, OrderEventType.REJECTED));
    }

    public void publishOrderModified(Object source, Order order) {
        applicationEventPublisher.publishEvent(new OrderEvent(source, order, OrderEventType.MODIFIED));
    }

    public void publishOrderPartiallyFilled(Object source, Order order, OrderStatus previousStatus) {
        applicationEventPublisher.publishEvent(
                new OrderEvent(source, order, OrderEventType.PARTIALLY_FILLED, previousStatus));
    }

    public void publishOrderTriggered(Object source, Order order) {
        applicationEventPublisher.publishEvent(new OrderEvent(source, order, OrderEventType.TRIGGERED));
    }

    // ---- Position ----

    public void publishPositionOpened(Object source, Position position) {
        applicationEventPublisher.publishEvent(new PositionEvent(source, position, PositionEventType.OPENED));
    }

    public void publishPositionUpdated(Object source, Position position, BigDecimal previousPnl) {
        applicationEventPublisher.publishEvent(
                new PositionEvent(source, position, PositionEventType.UPDATED, previousPnl));
    }

    public void publishPositionClosed(Object source, Position position, BigDecimal previousPnl) {
        applicationEventPublisher.publishEvent(
                new PositionEvent(source, position, PositionEventType.CLOSED, previousPnl));
    }

    public void publishPositionIncreased(Object source, Position position) {
        applicationEventPublisher.publishEvent(new PositionEvent(source, position, PositionEventType.INCREASED));
    }

    public void publishPositionReduced(Object source, Position position) {
        applicationEventPublisher.publishEvent(new PositionEvent(source, position, PositionEventType.REDUCED));
    }

    // ---- Strategy ----

    public void publishStrategyEvent(
            Object source, Strategy strategy, StrategyEventType eventType, StrategyStatus previousStatus) {
        applicationEventPublisher.publishEvent(new StrategyEvent(source, strategy, eventType, previousStatus));
    }

    public void publishStrategyCreated(Object source, Strategy strategy) {
        applicationEventPublisher.publishEvent(new StrategyEvent(source, strategy, StrategyEventType.CREATED));
    }

    public void publishStrategyArmed(Object source, Strategy strategy, StrategyStatus previousStatus) {
        applicationEventPublisher.publishEvent(
                new StrategyEvent(source, strategy, StrategyEventType.ARMED, previousStatus));
    }

    public void publishStrategyClosed(Object source, Strategy strategy, StrategyStatus previousStatus) {
        applicationEventPublisher.publishEvent(
                new StrategyEvent(source, strategy, StrategyEventType.CLOSED, previousStatus));
    }

    // ---- Risk ----

    public void publishRiskEvent(Object source, RiskEventType eventType, RiskLevel level, String message) {
        applicationEventPublisher.publishEvent(new RiskEvent(source, eventType, level, message));
    }

    public void publishRiskEvent(
            Object source, RiskEventType eventType, RiskLevel level, String message, Map<String, Object> details) {
        applicationEventPublisher.publishEvent(new RiskEvent(source, eventType, level, message, details));
    }

    // ---- Adjustment ----

    public void publishAdjustmentTriggered(
            Object source, String strategyId, AdjustmentRule rule, AdjustmentAction action, Position position) {
        applicationEventPublisher.publishEvent(new AdjustmentEvent(source, strategyId, rule, action, position));
    }

    public void publishAdjustmentCompleted(
            Object source, String strategyId, AdjustmentRule rule, AdjustmentAction action, Position position) {
        applicationEventPublisher.publishEvent(
                new AdjustmentEvent(source, strategyId, rule, action, position, AdjustmentStatus.COMPLETED));
    }

    public void publishAdjustmentFailed(
            Object source, String strategyId, AdjustmentRule rule, AdjustmentAction action, Position position) {
        applicationEventPublisher.publishEvent(
                new AdjustmentEvent(source, strategyId, rule, action, position, AdjustmentStatus.FAILED));
    }

    // ---- Session ----

    public void publishSessionEvent(
            Object source,
            SessionEventType eventType,
            SessionState previousState,
            SessionState newState,
            String message) {
        applicationEventPublisher.publishEvent(new SessionEvent(source, eventType, previousState, newState, message));
    }

    // ---- Market Status ----

    public void publishMarketPhaseTransition(Object source, MarketPhase previousPhase, MarketPhase currentPhase) {
        applicationEventPublisher.publishEvent(new MarketStatusEvent(source, previousPhase, currentPhase));
    }

    // ---- Reconciliation ----

    public void publishReconciliation(Object source, ReconciliationResult result, boolean manual) {
        applicationEventPublisher.publishEvent(new ReconciliationEvent(source, result, manual));
    }

    // ---- System ----

    public void publishSystemEvent(Object source, SystemEventType eventType, String message) {
        applicationEventPublisher.publishEvent(new SystemEvent(source, eventType, message));
    }

    public void publishSystemEvent(
            Object source, SystemEventType eventType, String message, Map<String, Object> details) {
        applicationEventPublisher.publishEvent(new SystemEvent(source, eventType, message, details));
    }

    // ---- Decision ----

    public void publishDecision(Object source, String category, String message) {
        applicationEventPublisher.publishEvent(new DecisionEvent(source, category, message));
    }

    public void publishDecision(
            Object source, String category, String message, String strategyId, Map<String, Object> context) {
        applicationEventPublisher.publishEvent(new DecisionEvent(source, category, message, strategyId, context));
    }
}
