package com.algotrader.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Position;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.oms.IdempotencyService;
import com.algotrader.oms.JournaledMultiLegExecutor;
import com.algotrader.oms.OrderQueue;
import com.algotrader.oms.OrderRequest;
import com.algotrader.oms.OrderRouteResult;
import com.algotrader.oms.OrderRouter;
import com.algotrader.repository.jpa.StrategyJpaRepository;
import com.algotrader.repository.jpa.StrategyLegJpaRepository;
import com.algotrader.repository.redis.OrderRedisRepository;
import com.algotrader.repository.redis.PositionRedisRepository;
import com.algotrader.risk.KillSwitchResult;
import com.algotrader.risk.KillSwitchService;
import com.algotrader.service.InstrumentService;
import com.algotrader.strategy.StrategyFactory;
import com.algotrader.strategy.impl.StraddleConfig;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Cross-service integration test for kill switch activation.
 * Wires real KillSwitchService + StrategyEngine + OrderRouter together
 * with broker and Redis mocked to verify the kill switch sequence:
 * activate -> pause strategies -> block orders -> cancel pending -> close positions.
 */
@ExtendWith(MockitoExtension.class)
class KillSwitchIntegrationTest {

    @Mock
    private BrokerGateway brokerGateway;

    @Mock
    private PositionRedisRepository positionRedisRepository;

    @Mock
    private OrderRedisRepository orderRedisRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private StrategyEngine strategyEngine;
    private OrderRouter orderRouter;
    private KillSwitchService killSwitchService;

    @BeforeEach
    void setUp() {
        EventPublisherHelper eventPublisherHelper = mock(EventPublisherHelper.class);
        JournaledMultiLegExecutor journaledMultiLegExecutor = mock(JournaledMultiLegExecutor.class);
        InstrumentService instrumentService = mock(InstrumentService.class);

        StrategyFactory strategyFactory = new StrategyFactory();
        StrategyJpaRepository strategyJpaRepository = mock(StrategyJpaRepository.class);
        StrategyLegJpaRepository strategyLegJpaRepository = mock(StrategyLegJpaRepository.class);
        strategyEngine = new StrategyEngine(
                strategyFactory,
                eventPublisherHelper,
                journaledMultiLegExecutor,
                instrumentService,
                strategyJpaRepository,
                strategyLegJpaRepository,
                positionRedisRepository);

        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        OrderQueue orderQueue = new OrderQueue();
        orderRouter = new OrderRouter(idempotencyService, orderQueue, eventPublisherHelper);

        killSwitchService = new KillSwitchService(
                strategyEngine,
                orderRouter,
                brokerGateway,
                positionRedisRepository,
                orderRedisRepository,
                applicationEventPublisher);
    }

    @Test
    @DisplayName("Kill switch pauses all strategies and blocks new orders")
    void killSwitch_pausesStrategiesAndBlocksOrders() {
        // Deploy and arm two strategies
        StraddleConfig config = buildConfig();

        String id1 = strategyEngine.deployStrategy(StrategyType.STRADDLE, "S1", config, true);
        String id2 = strategyEngine.deployStrategy(StrategyType.STRADDLE, "S2", config, true);

        // Setup broker mocks - no pending orders, no positions
        when(orderRedisRepository.findPending()).thenReturn(List.of());
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        // Activate kill switch
        KillSwitchResult result = killSwitchService.activate("Test: manual activation");

        // Verify strategies paused
        assertThat(strategyEngine.getStrategy(id1).getStatus()).isEqualTo(StrategyStatus.PAUSED);
        assertThat(strategyEngine.getStrategy(id2).getStatus()).isEqualTo(StrategyStatus.PAUSED);
        assertThat(result.isSuccess()).isTrue();

        // Verify regular orders are now blocked
        OrderRequest regularOrder = OrderRequest.builder()
                .tradingSymbol("NIFTY25FEB24500CE")
                .instrumentToken(12345L)
                .side(OrderSide.SELL)
                .type(OrderType.MARKET)
                .quantity(50)
                .correlationId("test-corr-1")
                .build();
        OrderRouteResult routeResult = orderRouter.route(regularOrder, OrderPriority.STRATEGY_ENTRY);
        assertThat(routeResult.isAccepted()).isFalse();
        assertThat(routeResult.getRejectionReason()).containsIgnoringCase("kill switch");
    }

    @Test
    @DisplayName("Kill switch cancels pending orders via broker")
    void killSwitch_cancelsPendingOrders() {
        Order pendingOrder = Order.builder()
                .brokerOrderId("KITE-ORD-001")
                .tradingSymbol("NIFTY25FEB24500CE")
                .status(com.algotrader.domain.enums.OrderStatus.OPEN)
                .build();
        when(orderRedisRepository.findPending()).thenReturn(List.of(pendingOrder));
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        KillSwitchResult result = killSwitchService.activate("Test: cancel orders");

        assertThat(result.isSuccess()).isTrue();
        verify(brokerGateway).cancelOrder("KITE-ORD-001");
    }

    @Test
    @DisplayName("Kill switch closes open positions with market orders")
    void killSwitch_closesPositions() {
        Position openPosition = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(120.50))
                .build();
        when(orderRedisRepository.findPending()).thenReturn(List.of());
        when(positionRedisRepository.findAll()).thenReturn(List.of(openPosition));

        KillSwitchResult result = killSwitchService.activate("Test: close positions");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getPositionsClosed()).isEqualTo(1);
        // Verify broker was called to place exit order
        verify(brokerGateway).placeOrder(any(Order.class));
    }

    @Test
    @DisplayName("Kill switch is idempotent - second activation returns not success")
    void killSwitch_idempotent() {
        when(orderRedisRepository.findPending()).thenReturn(List.of());
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        KillSwitchResult result1 = killSwitchService.activate("First");
        KillSwitchResult result2 = killSwitchService.activate("Second");

        assertThat(result1.isSuccess()).isTrue();
        assertThat(result2.isSuccess()).isFalse();
        assertThat(result2.getReason()).contains("already");
    }

    @Test
    @DisplayName("Kill switch can be deactivated and reactivated")
    void killSwitch_deactivateAndReactivate() {
        when(orderRedisRepository.findPending()).thenReturn(List.of());
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        killSwitchService.activate("First activation");
        assertThat(killSwitchService.isActive()).isTrue();

        killSwitchService.deactivate();
        assertThat(killSwitchService.isActive()).isFalse();

        KillSwitchResult result = killSwitchService.activate("Second activation");
        assertThat(result.isSuccess()).isTrue();
    }

    private StraddleConfig buildConfig() {
        return StraddleConfig.builder()
                .underlying("NIFTY")
                .lots(1)
                .targetPercent(BigDecimal.valueOf(0.5))
                .stopLossMultiplier(BigDecimal.valueOf(2.0))
                .minDaysToExpiry(1)
                .build();
    }
}
