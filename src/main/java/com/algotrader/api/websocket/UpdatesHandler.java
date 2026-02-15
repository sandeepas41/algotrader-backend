package com.algotrader.api.websocket;

import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Position;
import com.algotrader.event.OrderEvent;
import com.algotrader.event.PositionEvent;
import com.algotrader.event.RiskEvent;
import com.algotrader.event.StrategyEvent;
import com.algotrader.event.SystemEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Aggregates various platform events and pushes them to the frontend via
 * the {@code /topic/updates} WebSocket topic.
 *
 * <p>This is the primary real-time notification channel for the frontend dashboard.
 * All events are wrapped in the standard {@code { type, data }} envelope. The "type"
 * field tells the frontend which Zustand store should process the update:
 * <ul>
 *   <li>"ORDER" -- order status changes (placed, filled, rejected, etc.)</li>
 *   <li>"POSITION" -- position P&L and quantity updates</li>
 *   <li>"RISK" -- risk alerts and limit breaches</li>
 *   <li>"STRATEGY" -- strategy lifecycle transitions</li>
 *   <li>"SYSTEM" -- application lifecycle events (startup, shutdown, broker connect)</li>
 * </ul>
 *
 * <p>All handlers run async on the eventExecutor to avoid blocking the
 * internal event processing pipeline.
 */
@Component
public class UpdatesHandler {

    private static final Logger log = LoggerFactory.getLogger(UpdatesHandler.class);

    private final SimpMessagingTemplate simpMessagingTemplate;

    public UpdatesHandler(SimpMessagingTemplate simpMessagingTemplate) {
        this.simpMessagingTemplate = simpMessagingTemplate;
    }

    @Async("eventExecutor")
    @EventListener
    public void onOrderEvent(OrderEvent orderEvent) {
        Order order = orderEvent.getOrder();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", orderEvent.getEventType().name());
        payload.put("orderId", order.getId());
        payload.put("brokerOrderId", order.getBrokerOrderId());
        payload.put("tradingSymbol", order.getTradingSymbol());
        payload.put("exchange", order.getExchange());
        payload.put("side", order.getSide() != null ? order.getSide().name() : null);
        payload.put("type", order.getType() != null ? order.getType().name() : null);
        payload.put("quantity", order.getQuantity());
        payload.put("price", order.getPrice());
        payload.put("triggerPrice", order.getTriggerPrice());
        payload.put("status", order.getStatus() != null ? order.getStatus().name() : null);
        payload.put("filledQuantity", order.getFilledQuantity());
        payload.put("averageFillPrice", order.getAverageFillPrice());
        payload.put("strategyId", order.getStrategyId());
        payload.put("correlationId", order.getCorrelationId());

        sendUpdate("ORDER", payload);
    }

    @Async("eventExecutor")
    @EventListener
    public void onPositionEvent(PositionEvent positionEvent) {
        Position position = positionEvent.getPosition();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", positionEvent.getEventType().name());
        payload.put("positionId", position.getId());
        payload.put("tradingSymbol", position.getTradingSymbol());
        payload.put("exchange", position.getExchange());
        payload.put("quantity", position.getQuantity());
        payload.put("averagePrice", position.getAveragePrice());
        payload.put("lastPrice", position.getLastPrice());
        payload.put("unrealizedPnl", position.getUnrealizedPnl());
        payload.put("realizedPnl", position.getRealizedPnl());
        sendUpdate("POSITION", payload);
    }

    @Async("eventExecutor")
    @EventListener
    public void onRiskEvent(RiskEvent riskEvent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", riskEvent.getEventType().name());
        payload.put("level", riskEvent.getLevel().name());
        payload.put("message", riskEvent.getMessage());
        payload.put("details", riskEvent.getDetails());

        sendUpdate("RISK", payload);
    }

    @Async("eventExecutor")
    @EventListener
    public void onStrategyEvent(StrategyEvent strategyEvent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", strategyEvent.getEventType().name());
        payload.put("strategyId", strategyEvent.getStrategy().getId());
        payload.put("strategyName", strategyEvent.getStrategy().getName());
        payload.put(
                "strategyType",
                strategyEvent.getStrategy() != null
                                && strategyEvent.getStrategy().getType() != null
                        ? strategyEvent.getStrategy().getType().name()
                        : null);
        payload.put(
                "status",
                strategyEvent.getStrategy().getStatus() != null
                        ? strategyEvent.getStrategy().getStatus().name()
                        : null);
        payload.put(
                "previousStatus",
                strategyEvent.getPreviousStatus() != null
                        ? strategyEvent.getPreviousStatus().name()
                        : null);

        sendUpdate("STRATEGY", payload);
    }

    @Async("eventExecutor")
    @EventListener
    public void onSystemEvent(SystemEvent systemEvent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", systemEvent.getEventType().name());
        payload.put("message", systemEvent.getMessage());
        payload.put("details", systemEvent.getDetails());

        sendUpdate("SYSTEM", payload);
    }

    private void sendUpdate(String type, Object data) {
        try {
            simpMessagingTemplate.convertAndSend("/topic/updates", WebSocketMessage.of(type, data));
        } catch (Exception e) {
            log.error("Failed to send {} update via WebSocket: {}", type, e.getMessage());
        }
    }
}
