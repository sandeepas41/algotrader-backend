package com.algotrader.unit.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Position;
import com.algotrader.event.RiskEvent;
import com.algotrader.oms.OrderRouter;
import com.algotrader.repository.redis.OrderRedisRepository;
import com.algotrader.repository.redis.PositionRedisRepository;
import com.algotrader.risk.KillSwitchResult;
import com.algotrader.risk.KillSwitchService;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for KillSwitchService covering activation, idempotency,
 * parallel cancel/close, OMS bypass, retry on failure, and pause-all.
 */
@ExtendWith(MockitoExtension.class)
class KillSwitchServiceTest {

    @Mock
    private StrategyEngine strategyEngine;

    @Mock
    private OrderRouter orderRouter;

    @Mock
    private BrokerGateway brokerGateway;

    @Mock
    private PositionRedisRepository positionRedisRepository;

    @Mock
    private OrderRedisRepository orderRedisRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private KillSwitchService killSwitchService;

    @BeforeEach
    void setUp() {
        killSwitchService = new KillSwitchService(
                strategyEngine,
                orderRouter,
                brokerGateway,
                positionRedisRepository,
                orderRedisRepository,
                applicationEventPublisher);
    }

    // ==============================
    // ACTIVATION
    // ==============================

    @Nested
    @DisplayName("Kill Switch Activation")
    class Activation {

        @Test
        @DisplayName("Successful activation pauses strategies, cancels orders, closes positions")
        void successfulActivation() {
            when(strategyEngine.getActiveStrategies()).thenReturn(Map.of());
            when(orderRedisRepository.findPending())
                    .thenReturn(List.of(
                            Order.builder().id("ORD-001").brokerOrderId("B-001").build()));
            when(positionRedisRepository.findAll())
                    .thenReturn(List.of(Position.builder()
                            .id("POS-001")
                            .instrumentToken(12345L)
                            .tradingSymbol("NIFTY24FEB22000CE")
                            .exchange("NFO")
                            .quantity(-5)
                            .build()));

            KillSwitchResult result = killSwitchService.activate("Emergency test");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOrdersCancelled()).isEqualTo(1);
            assertThat(result.getPositionsClosed()).isEqualTo(1);
            assertThat(result.getReason()).isEqualTo("Emergency test");
            assertThat(killSwitchService.isActive()).isTrue();
        }

        @Test
        @DisplayName("Activation publishes CRITICAL risk event")
        void activation_publishesRiskEvent() {
            when(strategyEngine.getActiveStrategies()).thenReturn(Map.of());
            when(orderRedisRepository.findPending()).thenReturn(Collections.emptyList());
            when(positionRedisRepository.findAll()).thenReturn(Collections.emptyList());

            killSwitchService.activate("Test reason");

            verify(applicationEventPublisher).publishEvent(any(RiskEvent.class));
        }

        @Test
        @DisplayName("Activation activates kill switch on OrderRouter")
        void activation_activatesOrderRouter() {
            when(strategyEngine.getActiveStrategies()).thenReturn(Map.of());
            when(orderRedisRepository.findPending()).thenReturn(Collections.emptyList());
            when(positionRedisRepository.findAll()).thenReturn(Collections.emptyList());

            killSwitchService.activate("Test");

            verify(orderRouter).activateKillSwitch();
        }

        @Test
        @DisplayName("Activation pauses all strategies via StrategyEngine")
        void activation_pausesStrategies() {
            when(strategyEngine.getActiveStrategies()).thenReturn(Map.of());
            when(orderRedisRepository.findPending()).thenReturn(Collections.emptyList());
            when(positionRedisRepository.findAll()).thenReturn(Collections.emptyList());

            killSwitchService.activate("Test");

            verify(strategyEngine).pauseAll();
        }

        @Test
        @DisplayName("Kill switch bypasses OMS -- calls BrokerGateway.placeOrder() directly")
        void bypasses_oms() {
            when(strategyEngine.getActiveStrategies()).thenReturn(Map.of());
            when(orderRedisRepository.findPending()).thenReturn(Collections.emptyList());
            when(positionRedisRepository.findAll())
                    .thenReturn(List.of(Position.builder()
                            .id("POS-001")
                            .instrumentToken(12345L)
                            .tradingSymbol("NIFTY24FEB22000CE")
                            .exchange("NFO")
                            .quantity(-5)
                            .build()));

            killSwitchService.activate("Test");

            // Verify placeOrder called on BrokerGateway directly (not OrderRouter.route)
            verify(brokerGateway).placeOrder(any());
            verify(orderRouter, never()).route(any(), any());
        }
    }

    // ==============================
    // IDEMPOTENCY
    // ==============================

    @Nested
    @DisplayName("Kill Switch Idempotency")
    class Idempotency {

        @Test
        @DisplayName("Double activation returns alreadyActive result")
        void doubleActivation_returnsAlreadyActive() {
            when(strategyEngine.getActiveStrategies()).thenReturn(Map.of());
            when(orderRedisRepository.findPending()).thenReturn(Collections.emptyList());
            when(positionRedisRepository.findAll()).thenReturn(Collections.emptyList());

            KillSwitchResult first = killSwitchService.activate("First");
            KillSwitchResult second = killSwitchService.activate("Second");

            assertThat(first.isSuccess()).isTrue();
            assertThat(second.isSuccess()).isFalse();
            assertThat(second.getReason()).contains("already active");
        }
    }

    // ==============================
    // RETRY ON FAILURE
    // ==============================

    @Nested
    @DisplayName("Retry on Failure")
    class RetryOnFailure {

        @Test
        @DisplayName("Cancel retries up to 3 times, succeeds on 3rd attempt")
        void cancelRetry_succeedsOnThirdAttempt() {
            when(strategyEngine.getActiveStrategies()).thenReturn(Map.of());
            when(positionRedisRepository.findAll()).thenReturn(Collections.emptyList());

            Order pendingOrder =
                    Order.builder().id("ORD-001").brokerOrderId("B-001").build();
            when(orderRedisRepository.findPending()).thenReturn(List.of(pendingOrder));

            // First 2 attempts fail, 3rd succeeds
            doThrow(new RuntimeException("Broker error"))
                    .doThrow(new RuntimeException("Broker error"))
                    .doNothing()
                    .when(brokerGateway)
                    .cancelOrder("B-001");

            KillSwitchResult result = killSwitchService.activate("Test");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOrdersCancelled()).isEqualTo(1);
            verify(brokerGateway, times(3)).cancelOrder("B-001");
        }

        @Test
        @DisplayName("Close position retries up to 3 times, fails after all attempts")
        void closeRetry_failsAfterAllAttempts() {
            when(strategyEngine.getActiveStrategies()).thenReturn(Map.of());
            when(orderRedisRepository.findPending()).thenReturn(Collections.emptyList());

            Position position = Position.builder()
                    .id("POS-001")
                    .instrumentToken(12345L)
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .exchange("NFO")
                    .quantity(-5)
                    .build();
            when(positionRedisRepository.findAll()).thenReturn(List.of(position));

            // All 3 attempts fail
            when(brokerGateway.placeOrder(any())).thenThrow(new RuntimeException("Broker unavailable"));

            KillSwitchResult result = killSwitchService.activate("Test");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getPositionsClosed()).isEqualTo(0);
            assertThat(result.hasErrors()).isTrue();
            verify(brokerGateway, times(3)).placeOrder(any());
        }
    }

    // ==============================
    // DEACTIVATION
    // ==============================

    @Nested
    @DisplayName("Kill Switch Deactivation")
    class Deactivation {

        @Test
        @DisplayName("Deactivation resets kill switch and OrderRouter")
        void deactivation_resetsState() {
            when(strategyEngine.getActiveStrategies()).thenReturn(Map.of());
            when(orderRedisRepository.findPending()).thenReturn(Collections.emptyList());
            when(positionRedisRepository.findAll()).thenReturn(Collections.emptyList());

            killSwitchService.activate("Test");
            assertThat(killSwitchService.isActive()).isTrue();

            killSwitchService.deactivate();

            assertThat(killSwitchService.isActive()).isFalse();
            verify(orderRouter).deactivateKillSwitch();
        }
    }

    // ==============================
    // PAUSE ALL
    // ==============================

    @Nested
    @DisplayName("Pause All Strategies")
    class PauseAll {

        @Test
        @DisplayName("PauseAll pauses strategies without closing positions")
        void pauseAll_pausesWithoutClosing() {
            int paused = killSwitchService.pauseAllStrategies();

            verify(strategyEngine).pauseAll();
            // Kill switch should NOT be active
            assertThat(killSwitchService.isActive()).isFalse();
            // No orders cancelled, no positions closed
            verify(brokerGateway, never()).cancelOrder(any());
            verify(brokerGateway, never()).placeOrder(any());
        }
    }

    // ==============================
    // EDGE CASES
    // ==============================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Skips orders without brokerOrderId")
        void ordersWithoutBrokerId_skipped() {
            when(strategyEngine.getActiveStrategies()).thenReturn(Map.of());
            when(positionRedisRepository.findAll()).thenReturn(Collections.emptyList());

            // Order without brokerOrderId (not yet placed with broker)
            Order pendingOrder = Order.builder().id("ORD-001").build();
            when(orderRedisRepository.findPending()).thenReturn(List.of(pendingOrder));

            KillSwitchResult result = killSwitchService.activate("Test");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOrdersCancelled()).isEqualTo(0);
            verify(brokerGateway, never()).cancelOrder(any());
        }

        @Test
        @DisplayName("Skips positions with zero quantity")
        void zeroQuantityPositions_skipped() {
            when(strategyEngine.getActiveStrategies()).thenReturn(Map.of());
            when(orderRedisRepository.findPending()).thenReturn(Collections.emptyList());

            Position closedPosition = Position.builder()
                    .id("POS-001")
                    .instrumentToken(12345L)
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .exchange("NFO")
                    .quantity(0)
                    .build();
            when(positionRedisRepository.findAll()).thenReturn(List.of(closedPosition));

            KillSwitchResult result = killSwitchService.activate("Test");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getPositionsClosed()).isEqualTo(0);
            verify(brokerGateway, never()).placeOrder(any());
        }

        @Test
        @DisplayName("Empty positions and orders results in clean activation")
        void emptyState_cleanActivation() {
            when(strategyEngine.getActiveStrategies()).thenReturn(Map.of());
            when(orderRedisRepository.findPending()).thenReturn(Collections.emptyList());
            when(positionRedisRepository.findAll()).thenReturn(Collections.emptyList());

            KillSwitchResult result = killSwitchService.activate("Test");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOrdersCancelled()).isEqualTo(0);
            assertThat(result.getPositionsClosed()).isEqualTo(0);
        }
    }
}
