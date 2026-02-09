package com.algotrader.unit.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.algotrader.api.websocket.IndicatorStreamHandler;
import com.algotrader.api.websocket.WebSocketMessage;
import com.algotrader.event.IndicatorUpdateEvent;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Tests for IndicatorStreamHandler: verifies correct WebSocket routing
 * of indicator update events.
 */
@ExtendWith(MockitoExtension.class)
class IndicatorStreamHandlerTest {

    @Mock
    private SimpMessagingTemplate simpMessagingTemplate;

    private IndicatorStreamHandler indicatorStreamHandler;

    @BeforeEach
    void setUp() {
        indicatorStreamHandler = new IndicatorStreamHandler(simpMessagingTemplate);
    }

    @Test
    @DisplayName("sends indicator update to per-instrument topic")
    @SuppressWarnings("unchecked")
    void sendsToPerInstrumentTopic() {
        Map<String, BigDecimal> indicators = Map.of("RSI:14", BigDecimal.valueOf(65.5));
        IndicatorUpdateEvent event = new IndicatorUpdateEvent(this, 256265L, "NIFTY", indicators);

        indicatorStreamHandler.onIndicatorUpdate(event);

        ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(simpMessagingTemplate).convertAndSend(eq("/topic/indicators/256265"), captor.capture());

        WebSocketMessage message = captor.getValue();
        assertThat(message.getType()).isEqualTo("INDICATOR_UPDATE");

        Map<String, Object> payload = (Map<String, Object>) message.getData();
        assertThat(payload).containsEntry("instrumentToken", 256265L);
        assertThat(payload).containsEntry("tradingSymbol", "NIFTY");
        assertThat(payload).containsKey("indicators");
        assertThat(payload).containsKey("timestamp");
    }

    @Test
    @DisplayName("sends indicator update to general /topic/updates")
    @SuppressWarnings("unchecked")
    void sendsToGeneralUpdatesTopic() {
        Map<String, BigDecimal> indicators = Map.of("EMA:21", BigDecimal.valueOf(22550.75));
        IndicatorUpdateEvent event = new IndicatorUpdateEvent(this, 256265L, "NIFTY", indicators);

        indicatorStreamHandler.onIndicatorUpdate(event);

        ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(simpMessagingTemplate).convertAndSend(eq("/topic/updates"), captor.capture());

        WebSocketMessage message = captor.getValue();
        assertThat(message.getType()).isEqualTo("INDICATOR");

        Map<String, Object> payload = (Map<String, Object>) message.getData();
        assertThat(payload).containsEntry("instrumentToken", 256265L);
    }

    @Test
    @DisplayName("sends to both topics on every update")
    void sendsToBothTopics() {
        Map<String, BigDecimal> indicators = Map.of("ATR:14", BigDecimal.valueOf(150.25));
        IndicatorUpdateEvent event = new IndicatorUpdateEvent(this, 256265L, "NIFTY", indicators);

        indicatorStreamHandler.onIndicatorUpdate(event);

        // Two convertAndSend calls: one per-instrument, one general
        verify(simpMessagingTemplate, times(2))
                .convertAndSend(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(WebSocketMessage.class));
    }
}
