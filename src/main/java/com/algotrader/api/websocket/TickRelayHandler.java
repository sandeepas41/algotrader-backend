package com.algotrader.api.websocket;

import com.algotrader.domain.model.Tick;
import com.algotrader.event.TickEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Relays market data ticks from the Kite WebSocket to the frontend via STOMP.
 *
 * <p>Listens to {@link TickEvent} published by the market data service and sends
 * each tick to an instrument-specific topic: {@code /topic/ticks/{instrumentToken}}.
 * The frontend subscribes to individual instrument topics based on the active
 * watchlist and open positions.
 *
 * <p>Important: ticks are sent to instrument-specific topics, NOT a global
 * {@code /topic/ticks} topic. This prevents bandwidth waste by only delivering
 * ticks the client cares about.
 *
 * <p>This handler runs at {@code @Order(10)} (after all processing handlers)
 * and is async to avoid blocking the tick processing pipeline.
 *
 * <p>The message format is {@code { type: "TICK", data: {...} }}.
 */
@Component
public class TickRelayHandler {

    private static final Logger log = LoggerFactory.getLogger(TickRelayHandler.class);

    private final SimpMessagingTemplate simpMessagingTemplate;

    public TickRelayHandler(SimpMessagingTemplate simpMessagingTemplate) {
        this.simpMessagingTemplate = simpMessagingTemplate;
    }

    @Async("eventExecutor")
    @EventListener
    @Order(10)
    public void onTick(TickEvent tickEvent) {
        Tick tick = tickEvent.getTick();

        Map<String, Object> payload = toPayload(tick);
        WebSocketMessage message = WebSocketMessage.of("TICK", payload);

        try {
            // Send to instrument-specific topic (NOT global /topic/ticks)
            simpMessagingTemplate.convertAndSend("/topic/ticks/" + tick.getInstrumentToken(), message);
        } catch (Exception e) {
            log.error("Failed to relay tick for instrument {}: {}", tick.getInstrumentToken(), e.getMessage());
        }
    }

    private Map<String, Object> toPayload(Tick tick) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrumentToken", tick.getInstrumentToken());
        payload.put("lastPrice", tick.getLastPrice());
        payload.put("open", tick.getOpen());
        payload.put("high", tick.getHigh());
        payload.put("low", tick.getLow());
        payload.put("close", tick.getClose());
        payload.put("volume", tick.getVolume());
        payload.put("buyQuantity", tick.getBuyQuantity());
        payload.put("sellQuantity", tick.getSellQuantity());
        payload.put("oi", tick.getOi());
        payload.put("oiChange", tick.getOiChange());
        payload.put("timestamp", tick.getTimestamp());
        return payload;
    }
}
