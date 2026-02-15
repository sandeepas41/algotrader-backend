package com.algotrader.unit.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.algotrader.api.websocket.UpdatesHandler;
import com.algotrader.api.websocket.WebSocketMessage;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.Strategy;
import com.algotrader.event.OrderEvent;
import com.algotrader.event.OrderEventType;
import com.algotrader.event.PositionEvent;
import com.algotrader.event.PositionEventType;
import com.algotrader.event.RiskEvent;
import com.algotrader.event.RiskEventType;
import com.algotrader.event.RiskLevel;
import com.algotrader.event.StrategyEvent;
import com.algotrader.event.StrategyEventType;
import com.algotrader.event.SystemEvent;
import com.algotrader.event.SystemEventType;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Tests that the UpdatesHandler correctly routes different event types
 * to /topic/updates with the proper type discriminator and payload.
 */
@ExtendWith(MockitoExtension.class)
class UpdatesHandlerTest {

    @Mock
    private SimpMessagingTemplate simpMessagingTemplate;

    private UpdatesHandler updatesHandler;

    @BeforeEach
    void setUp() {
        updatesHandler = new UpdatesHandler(simpMessagingTemplate);
    }

    @Nested
    @DisplayName("Order events")
    class OrderEvents {

        @Test
        @DisplayName("routes order event with type ORDER to /topic/updates")
        @SuppressWarnings("unchecked")
        void routesOrderEventToUpdates() {
            Order order = Order.builder()
                    .id("ORD-001")
                    .brokerOrderId("KT-123")
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .exchange("NFO")
                    .side(OrderSide.BUY)
                    .type(OrderType.MARKET)
                    .quantity(50)
                    .price(BigDecimal.valueOf(150.00))
                    .status(OrderStatus.OPEN)
                    .filledQuantity(0)
                    .strategyId("STR-001")
                    .correlationId("COR-001")
                    .build();

            OrderEvent event = new OrderEvent(this, order, OrderEventType.PLACED);
            updatesHandler.onOrderEvent(event);

            ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
            verify(simpMessagingTemplate).convertAndSend(eq("/topic/updates"), captor.capture());

            WebSocketMessage message = captor.getValue();
            assertThat(message.getType()).isEqualTo("ORDER");

            Map<String, Object> payload = (Map<String, Object>) message.getData();
            assertThat(payload).containsEntry("eventType", "PLACED");
            assertThat(payload).containsEntry("tradingSymbol", "NIFTY24FEB22000CE");
            assertThat(payload).containsEntry("side", "BUY");
            assertThat(payload).containsEntry("status", "OPEN");
            assertThat(payload).containsEntry("strategyId", "STR-001");
        }
    }

    @Nested
    @DisplayName("Position events")
    class PositionEvents {

        @Test
        @DisplayName("routes position event with type POSITION to /topic/updates")
        @SuppressWarnings("unchecked")
        void routesPositionEventToUpdates() {
            Position position = Position.builder()
                    .id("POS-001")
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .exchange("NFO")
                    .quantity(50)
                    .averagePrice(BigDecimal.valueOf(150.00))
                    .lastPrice(BigDecimal.valueOf(165.00))
                    .unrealizedPnl(BigDecimal.valueOf(750.00))
                    .realizedPnl(BigDecimal.ZERO)
                    .build();

            PositionEvent event = new PositionEvent(this, position, PositionEventType.UPDATED);
            updatesHandler.onPositionEvent(event);

            ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
            verify(simpMessagingTemplate).convertAndSend(eq("/topic/updates"), captor.capture());

            WebSocketMessage message = captor.getValue();
            assertThat(message.getType()).isEqualTo("POSITION");

            Map<String, Object> payload = (Map<String, Object>) message.getData();
            assertThat(payload).containsEntry("eventType", "UPDATED");
            assertThat(payload).containsEntry("tradingSymbol", "NIFTY24FEB22000CE");
            assertThat(payload).containsEntry("unrealizedPnl", BigDecimal.valueOf(750.00));
        }
    }

    @Nested
    @DisplayName("Risk events")
    class RiskEvents {

        @Test
        @DisplayName("routes risk event with type RISK to /topic/updates")
        @SuppressWarnings("unchecked")
        void routesRiskEventToUpdates() {
            RiskEvent event = new RiskEvent(
                    this,
                    RiskEventType.DAILY_LOSS_LIMIT_BREACH,
                    RiskLevel.CRITICAL,
                    "Daily loss limit breached: -50000 exceeds -40000",
                    Map.of("currentLoss", -50000, "limit", -40000));

            updatesHandler.onRiskEvent(event);

            ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
            verify(simpMessagingTemplate).convertAndSend(eq("/topic/updates"), captor.capture());

            WebSocketMessage message = captor.getValue();
            assertThat(message.getType()).isEqualTo("RISK");

            Map<String, Object> payload = (Map<String, Object>) message.getData();
            assertThat(payload).containsEntry("eventType", "DAILY_LOSS_LIMIT_BREACH");
            assertThat(payload).containsEntry("level", "CRITICAL");
            assertThat(payload).containsKey("message");
            assertThat(payload).containsKey("details");
        }
    }

    @Nested
    @DisplayName("Strategy events")
    class StrategyEvents {

        @Test
        @DisplayName("routes strategy event with type STRATEGY to /topic/updates")
        @SuppressWarnings("unchecked")
        void routesStrategyEventToUpdates() {
            Strategy strategy = Strategy.builder()
                    .id("STR-001")
                    .name("Nifty Iron Condor")
                    .type(StrategyType.IRON_CONDOR)
                    .status(StrategyStatus.ACTIVE)
                    .build();

            StrategyEvent event = new StrategyEvent(this, strategy, StrategyEventType.ACTIVE, StrategyStatus.ARMED);
            updatesHandler.onStrategyEvent(event);

            ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
            verify(simpMessagingTemplate).convertAndSend(eq("/topic/updates"), captor.capture());

            WebSocketMessage message = captor.getValue();
            assertThat(message.getType()).isEqualTo("STRATEGY");

            Map<String, Object> payload = (Map<String, Object>) message.getData();
            assertThat(payload).containsEntry("eventType", "ACTIVE");
            assertThat(payload).containsEntry("strategyId", "STR-001");
            assertThat(payload).containsEntry("strategyName", "Nifty Iron Condor");
            assertThat(payload).containsEntry("strategyType", "IRON_CONDOR");
            assertThat(payload).containsEntry("status", "ACTIVE");
            assertThat(payload).containsEntry("previousStatus", "ARMED");
        }
    }

    @Nested
    @DisplayName("System events")
    class SystemEvents {

        @Test
        @DisplayName("routes system event with type SYSTEM to /topic/updates")
        @SuppressWarnings("unchecked")
        void routesSystemEventToUpdates() {
            SystemEvent event = new SystemEvent(
                    this,
                    SystemEventType.APPLICATION_READY,
                    "Application started successfully",
                    Map.of("startupDurationMs", 3500));

            updatesHandler.onSystemEvent(event);

            ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
            verify(simpMessagingTemplate).convertAndSend(eq("/topic/updates"), captor.capture());

            WebSocketMessage message = captor.getValue();
            assertThat(message.getType()).isEqualTo("SYSTEM");

            Map<String, Object> payload = (Map<String, Object>) message.getData();
            assertThat(payload).containsEntry("eventType", "APPLICATION_READY");
            assertThat(payload).containsEntry("message", "Application started successfully");
            assertThat(payload).containsKey("details");
        }
    }

    @Nested
    @DisplayName("All events route to same topic")
    class AllEventsRouteToBroadcastTopic {

        @Test
        @DisplayName("all 5 event types send to /topic/updates")
        void allFiveEventTypesRouteToUpdates() {
            Order order = Order.builder()
                    .id("ORD-TEST")
                    .tradingSymbol("TEST")
                    .exchange("NFO")
                    .status(OrderStatus.OPEN)
                    .build();
            Position position = Position.builder()
                    .id("POS-TEST")
                    .tradingSymbol("TEST")
                    .exchange("NFO")
                    .build();
            Strategy strategy = Strategy.builder()
                    .id("STR-001")
                    .name("Test")
                    .type(StrategyType.STRADDLE)
                    .status(StrategyStatus.CREATED)
                    .build();

            updatesHandler.onOrderEvent(new OrderEvent(this, order, OrderEventType.PLACED));
            updatesHandler.onPositionEvent(new PositionEvent(this, position, PositionEventType.OPENED));
            updatesHandler.onRiskEvent(
                    new RiskEvent(this, RiskEventType.MARGIN_UTILIZATION_HIGH, RiskLevel.WARNING, "High margin"));
            updatesHandler.onStrategyEvent(new StrategyEvent(this, strategy, StrategyEventType.CREATED));
            updatesHandler.onSystemEvent(new SystemEvent(this, SystemEventType.BROKER_CONNECTED, "Broker connected"));

            // All 5 events should go to /topic/updates
            verify(simpMessagingTemplate, org.mockito.Mockito.times(5))
                    .convertAndSend(eq("/topic/updates"), any(WebSocketMessage.class));
        }
    }
}
