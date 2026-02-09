package com.algotrader.unit.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.algotrader.domain.enums.MarketPhase;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.AdjustmentAction;
import com.algotrader.domain.model.AdjustmentRule;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.ReconciliationResult;
import com.algotrader.domain.model.Strategy;
import com.algotrader.domain.model.Tick;
import com.algotrader.event.AdjustmentEvent;
import com.algotrader.event.AdjustmentStatus;
import com.algotrader.event.DecisionEvent;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.event.MarketStatusEvent;
import com.algotrader.event.OrderEvent;
import com.algotrader.event.OrderEventType;
import com.algotrader.event.PositionEvent;
import com.algotrader.event.PositionEventType;
import com.algotrader.event.ReconciliationEvent;
import com.algotrader.event.RiskEvent;
import com.algotrader.event.RiskEventType;
import com.algotrader.event.RiskLevel;
import com.algotrader.event.SessionEvent;
import com.algotrader.event.SessionEventType;
import com.algotrader.event.StrategyEvent;
import com.algotrader.event.StrategyEventType;
import com.algotrader.event.SystemEvent;
import com.algotrader.event.SystemEventType;
import com.algotrader.event.TickEvent;
import com.algotrader.session.SessionState;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link EventPublisherHelper}.
 *
 * <p>Verifies that each typed publish method creates the correct event type
 * with the expected fields and delegates to Spring's ApplicationEventPublisher.
 */
@ExtendWith(MockitoExtension.class)
class EventPublisherHelperTest {

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private EventPublisherHelper eventPublisherHelper;

    @BeforeEach
    void setUp() {
        eventPublisherHelper = new EventPublisherHelper(applicationEventPublisher);
    }

    @Test
    @DisplayName("publishTick creates TickEvent with tick and receivedAt")
    void publishTick() {
        Tick tick = Tick.builder()
                .instrumentToken(256265L)
                .lastPrice(new BigDecimal("22500.50"))
                .build();

        eventPublisherHelper.publishTick(this, tick);

        ArgumentCaptor<TickEvent> captor = ArgumentCaptor.forClass(TickEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        TickEvent event = captor.getValue();
        assertThat(event.getTick()).isSameAs(tick);
        assertThat(event.getReceivedAt()).isGreaterThan(0);
    }

    @Test
    @DisplayName("publishOrderPlaced creates OrderEvent with PLACED type")
    void publishOrderPlaced() {
        Order order = Order.builder()
                .tradingSymbol("NIFTY24FEB22000CE")
                .side(OrderSide.BUY)
                .type(OrderType.MARKET)
                .quantity(50)
                .build();

        eventPublisherHelper.publishOrderPlaced(this, order);

        ArgumentCaptor<OrderEvent> captor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        OrderEvent event = captor.getValue();
        assertThat(event.getOrder()).isSameAs(order);
        assertThat(event.getEventType()).isEqualTo(OrderEventType.PLACED);
        assertThat(event.getPreviousStatus()).isNull();
    }

    @Test
    @DisplayName("publishOrderFilled includes previous status")
    void publishOrderFilled() {
        Order order = Order.builder()
                .tradingSymbol("NIFTY24FEB22000CE")
                .status(OrderStatus.COMPLETE)
                .build();

        eventPublisherHelper.publishOrderFilled(this, order, OrderStatus.OPEN);

        ArgumentCaptor<OrderEvent> captor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        OrderEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(OrderEventType.FILLED);
        assertThat(event.getPreviousStatus()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    @DisplayName("publishPositionOpened creates PositionEvent with OPENED type")
    void publishPositionOpened() {
        Position position = Position.builder()
                .instrumentToken(256265L)
                .tradingSymbol("NIFTY24FEB22000CE")
                .quantity(50)
                .build();

        eventPublisherHelper.publishPositionOpened(this, position);

        ArgumentCaptor<PositionEvent> captor = ArgumentCaptor.forClass(PositionEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        PositionEvent event = captor.getValue();
        assertThat(event.getPosition()).isSameAs(position);
        assertThat(event.getEventType()).isEqualTo(PositionEventType.OPENED);
        assertThat(event.getPreviousPnl()).isNull();
    }

    @Test
    @DisplayName("publishPositionUpdated includes previous P&L")
    void publishPositionUpdated() {
        Position position = Position.builder()
                .instrumentToken(256265L)
                .unrealizedPnl(new BigDecimal("5000"))
                .build();

        eventPublisherHelper.publishPositionUpdated(this, position, new BigDecimal("4500"));

        ArgumentCaptor<PositionEvent> captor = ArgumentCaptor.forClass(PositionEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        PositionEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(PositionEventType.UPDATED);
        assertThat(event.getPreviousPnl()).isEqualByComparingTo("4500");
    }

    @Test
    @DisplayName("publishStrategyCreated creates StrategyEvent with CREATED type")
    void publishStrategyCreated() {
        Strategy strategy = Strategy.builder()
                .id("strat-1")
                .name("NIFTY Straddle")
                .type(StrategyType.STRADDLE)
                .build();

        eventPublisherHelper.publishStrategyCreated(this, strategy);

        ArgumentCaptor<StrategyEvent> captor = ArgumentCaptor.forClass(StrategyEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        StrategyEvent event = captor.getValue();
        assertThat(event.getStrategy()).isSameAs(strategy);
        assertThat(event.getEventType()).isEqualTo(StrategyEventType.CREATED);
        assertThat(event.getPreviousStatus()).isNull();
    }

    @Test
    @DisplayName("publishStrategyArmed includes previous status")
    void publishStrategyArmed() {
        Strategy strategy =
                Strategy.builder().id("strat-1").status(StrategyStatus.ARMED).build();

        eventPublisherHelper.publishStrategyArmed(this, strategy, StrategyStatus.CREATED);

        ArgumentCaptor<StrategyEvent> captor = ArgumentCaptor.forClass(StrategyEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        StrategyEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(StrategyEventType.ARMED);
        assertThat(event.getPreviousStatus()).isEqualTo(StrategyStatus.CREATED);
    }

    @Test
    @DisplayName("publishRiskEvent creates RiskEvent with details map")
    void publishRiskEvent() {
        Map<String, Object> details = Map.of("currentLoss", -50000, "limit", -40000);

        eventPublisherHelper.publishRiskEvent(
                this, RiskEventType.DAILY_LOSS_LIMIT_BREACH, RiskLevel.CRITICAL, "Daily loss limit exceeded", details);

        ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        RiskEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(RiskEventType.DAILY_LOSS_LIMIT_BREACH);
        assertThat(event.getLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(event.getMessage()).isEqualTo("Daily loss limit exceeded");
        assertThat(event.getDetails()).containsEntry("currentLoss", -50000);
    }

    @Test
    @DisplayName("publishAdjustmentTriggered creates AdjustmentEvent with PENDING status")
    void publishAdjustmentTriggered() {
        AdjustmentRule rule = AdjustmentRule.builder().name("Delta drift").build();
        AdjustmentAction action = AdjustmentAction.builder().build();
        Position position =
                Position.builder().tradingSymbol("NIFTY24FEB22000CE").build();

        eventPublisherHelper.publishAdjustmentTriggered(this, "strat-1", rule, action, position);

        ArgumentCaptor<AdjustmentEvent> captor = ArgumentCaptor.forClass(AdjustmentEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        AdjustmentEvent event = captor.getValue();
        assertThat(event.getStrategyId()).isEqualTo("strat-1");
        assertThat(event.getRule()).isSameAs(rule);
        assertThat(event.getAction()).isSameAs(action);
        assertThat(event.getAffectedPosition()).isSameAs(position);
        assertThat(event.getStatus()).isEqualTo(AdjustmentStatus.PENDING);
    }

    @Test
    @DisplayName("publishSessionEvent creates SessionEvent with state transition")
    void publishSessionEvent() {
        eventPublisherHelper.publishSessionEvent(
                this,
                SessionEventType.SESSION_EXPIRED,
                SessionState.ACTIVE,
                SessionState.EXPIRED,
                "Health check failed 3 times");

        ArgumentCaptor<SessionEvent> captor = ArgumentCaptor.forClass(SessionEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        SessionEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(SessionEventType.SESSION_EXPIRED);
        assertThat(event.getPreviousState()).isEqualTo(SessionState.ACTIVE);
        assertThat(event.getNewState()).isEqualTo(SessionState.EXPIRED);
        assertThat(event.getMessage()).isEqualTo("Health check failed 3 times");
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("publishMarketPhaseTransition creates MarketStatusEvent")
    void publishMarketPhaseTransition() {
        eventPublisherHelper.publishMarketPhaseTransition(this, MarketPhase.PRE_OPEN, MarketPhase.NORMAL);

        ArgumentCaptor<MarketStatusEvent> captor = ArgumentCaptor.forClass(MarketStatusEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        MarketStatusEvent event = captor.getValue();
        assertThat(event.getPreviousPhase()).isEqualTo(MarketPhase.PRE_OPEN);
        assertThat(event.getCurrentPhase()).isEqualTo(MarketPhase.NORMAL);
        assertThat(event.getTransitionTime()).isNotNull();
    }

    @Test
    @DisplayName("publishReconciliation creates ReconciliationEvent with result and manual flag")
    void publishReconciliation() {
        ReconciliationResult result = ReconciliationResult.builder()
                .trigger("MANUAL")
                .mismatches(List.of())
                .localPositionCount(5)
                .brokerPositionCount(5)
                .timestamp(LocalDateTime.now())
                .build();

        eventPublisherHelper.publishReconciliation(this, result, false);

        ArgumentCaptor<ReconciliationEvent> captor = ArgumentCaptor.forClass(ReconciliationEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        ReconciliationEvent event = captor.getValue();
        assertThat(event.getResult()).isSameAs(result);
        assertThat(event.isManual()).isFalse();
        assertThat(event.getReconciledAt()).isNotNull();
    }

    @Test
    @DisplayName("publishSystemEvent creates SystemEvent with type and message")
    void publishSystemEvent() {
        eventPublisherHelper.publishSystemEvent(this, SystemEventType.APPLICATION_READY, "All subsystems initialized");

        ArgumentCaptor<SystemEvent> captor = ArgumentCaptor.forClass(SystemEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        SystemEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(SystemEventType.APPLICATION_READY);
        assertThat(event.getMessage()).isEqualTo("All subsystems initialized");
        assertThat(event.getDetails()).isEmpty();
    }

    @Test
    @DisplayName("publishDecision creates DecisionEvent with category and context")
    void publishDecision() {
        Map<String, Object> context = Map.of("iv", 15.2, "threshold", 14.0);

        eventPublisherHelper.publishDecision(this, "ENTRY", "Straddle entry triggered", "strat-1", context);

        ArgumentCaptor<DecisionEvent> captor = ArgumentCaptor.forClass(DecisionEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        DecisionEvent event = captor.getValue();
        assertThat(event.getCategory()).isEqualTo("ENTRY");
        assertThat(event.getMessage()).isEqualTo("Straddle entry triggered");
        assertThat(event.getStrategyId()).isEqualTo("strat-1");
        assertThat(event.getContext()).containsEntry("iv", 15.2);
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("publishDecision with minimal arguments (no strategy, no context)")
    void publishDecisionMinimal() {
        eventPublisherHelper.publishDecision(this, "SYSTEM", "Kill switch activated");

        ArgumentCaptor<DecisionEvent> captor = ArgumentCaptor.forClass(DecisionEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        DecisionEvent event = captor.getValue();
        assertThat(event.getCategory()).isEqualTo("SYSTEM");
        assertThat(event.getStrategyId()).isNull();
        assertThat(event.getContext()).isEmpty();
    }
}
