package com.algotrader.unit.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.algotrader.api.websocket.TickRelayHandler;
import com.algotrader.api.websocket.WebSocketMessage;
import com.algotrader.domain.model.Tick;
import com.algotrader.event.TickEvent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * Tests that the TickRelayHandler sends tick data to instrument-specific
 * STOMP topics (not a global /topic/ticks) with the { type, data } envelope.
 */
@ExtendWith(MockitoExtension.class)
class TickRelayHandlerTest {

    @Mock
    private SimpMessagingTemplate simpMessagingTemplate;

    private TickRelayHandler tickRelayHandler;

    @BeforeEach
    void setUp() {
        tickRelayHandler = new TickRelayHandler(simpMessagingTemplate);
    }

    private Tick buildTick(long instrumentToken, BigDecimal lastPrice) {
        return Tick.builder()
                .instrumentToken(instrumentToken)
                .lastPrice(lastPrice)
                .open(BigDecimal.valueOf(22400))
                .high(BigDecimal.valueOf(22600))
                .low(BigDecimal.valueOf(22300))
                .close(BigDecimal.valueOf(22450))
                .volume(1500000L)
                .buyQuantity(BigDecimal.valueOf(500000))
                .sellQuantity(BigDecimal.valueOf(600000))
                .oi(BigDecimal.ZERO)
                .oiChange(BigDecimal.ZERO)
                .timestamp(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("Topic routing")
    class TopicRouting {

        @Test
        @DisplayName("sends to instrument-specific topic /topic/ticks/{instrumentToken}")
        void sendsToInstrumentSpecificTopic() {
            Tick tick = buildTick(256265, BigDecimal.valueOf(22500.50));
            TickEvent event = new TickEvent(this, tick);

            tickRelayHandler.onTick(event);

            verify(simpMessagingTemplate).convertAndSend(eq("/topic/ticks/256265"), any(WebSocketMessage.class));
        }

        @Test
        @DisplayName("does NOT send to global /topic/ticks")
        void doesNotSendToGlobalTopic() {
            Tick tick = buildTick(256265, BigDecimal.valueOf(22500));
            TickEvent event = new TickEvent(this, tick);

            tickRelayHandler.onTick(event);

            verify(simpMessagingTemplate, never()).convertAndSend(eq("/topic/ticks"), any(WebSocketMessage.class));
        }

        @Test
        @DisplayName("different instrument tokens route to different topics")
        void differentTokensRouteToDifferentTopics() {
            Tick niftyTick = buildTick(256265, BigDecimal.valueOf(22500));
            Tick bnfTick = buildTick(260105, BigDecimal.valueOf(48000));

            tickRelayHandler.onTick(new TickEvent(this, niftyTick));
            tickRelayHandler.onTick(new TickEvent(this, bnfTick));

            verify(simpMessagingTemplate).convertAndSend(eq("/topic/ticks/256265"), any(WebSocketMessage.class));
            verify(simpMessagingTemplate).convertAndSend(eq("/topic/ticks/260105"), any(WebSocketMessage.class));
        }
    }

    @Nested
    @DisplayName("Message format")
    class MessageFormat {

        @Test
        @DisplayName("wraps tick in TICK type envelope")
        void wrapsTickInTypeEnvelope() {
            Tick tick = buildTick(256265, BigDecimal.valueOf(22500));
            TickEvent event = new TickEvent(this, tick);

            tickRelayHandler.onTick(event);

            ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
            verify(simpMessagingTemplate).convertAndSend(eq("/topic/ticks/256265"), captor.capture());

            WebSocketMessage message = captor.getValue();
            assertThat(message.getType()).isEqualTo("TICK");
            assertThat(message.getData()).isInstanceOf(Map.class);
        }

        @Test
        @DisplayName("payload contains all tick fields")
        @SuppressWarnings("unchecked")
        void payloadContainsAllTickFields() {
            Tick tick = buildTick(256265, BigDecimal.valueOf(22500.75));
            TickEvent event = new TickEvent(this, tick);

            tickRelayHandler.onTick(event);

            ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
            verify(simpMessagingTemplate).convertAndSend(eq("/topic/ticks/256265"), captor.capture());

            Map<String, Object> payload =
                    (Map<String, Object>) captor.getValue().getData();
            assertThat(payload).containsEntry("instrumentToken", 256265L);
            assertThat(payload).containsEntry("lastPrice", BigDecimal.valueOf(22500.75));
            assertThat(payload).containsKey("open");
            assertThat(payload).containsKey("high");
            assertThat(payload).containsKey("low");
            assertThat(payload).containsKey("close");
            assertThat(payload).containsKey("volume");
            assertThat(payload).containsKey("buyQuantity");
            assertThat(payload).containsKey("sellQuantity");
            assertThat(payload).containsKey("oi");
            assertThat(payload).containsKey("oiChange");
            assertThat(payload).containsKey("timestamp");
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("does not propagate exception when send fails")
        void doesNotPropagateExceptionOnSendFailure() {
            Tick tick = buildTick(256265, BigDecimal.valueOf(22500));
            TickEvent event = new TickEvent(this, tick);

            doThrow(new RuntimeException("WebSocket disconnected"))
                    .when(simpMessagingTemplate)
                    .convertAndSend(any(String.class), any(WebSocketMessage.class));

            // Should not throw
            tickRelayHandler.onTick(event);
        }
    }
}
