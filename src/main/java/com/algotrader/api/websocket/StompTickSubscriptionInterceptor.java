package com.algotrader.api.websocket;

import com.algotrader.broker.InstrumentSubscriptionManager;
import com.algotrader.broker.KiteMarketDataService;
import com.algotrader.domain.enums.SubscriptionPriority;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * Intercepts STOMP SUBSCRIBE/UNSUBSCRIBE frames on the inbound channel to
 * automatically subscribe/unsubscribe Kite instrument tokens.
 *
 * <p>When the frontend subscribes to {@code /topic/ticks/{instrumentToken}}, this
 * interceptor extracts the token from the destination, registers it with the
 * {@link InstrumentSubscriptionManager} (MANUAL priority), and tells
 * {@link KiteMarketDataService} to subscribe the token with Kite's WebSocket.
 *
 * <p>On UNSUBSCRIBE, it looks up the token from the stored session→subscription mapping
 * and unsubscribes if no other subscriber needs it. On DISCONNECT (graceful), it
 * cleans up all subscriptions for that session.
 *
 * <p>For abrupt disconnects (browser tab close), {@link StompSessionDisconnectListener}
 * calls {@link #handleDisconnect(String)} separately via Spring's SessionDisconnectEvent.
 */
@Component
public class StompTickSubscriptionInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompTickSubscriptionInterceptor.class);
    private static final String TICK_TOPIC_PREFIX = "/topic/ticks/";

    private final InstrumentSubscriptionManager instrumentSubscriptionManager;
    private final KiteMarketDataService kiteMarketDataService;

    /**
     * Tracks per-session STOMP subscription mappings: sessionId → (subscriptionId → instrumentToken).
     * Needed because UNSUBSCRIBE frames carry only the subscriptionId, not the destination.
     */
    private final Map<String, Map<String, Long>> sessionSubscriptions = new ConcurrentHashMap<>();

    public StompTickSubscriptionInterceptor(
            InstrumentSubscriptionManager instrumentSubscriptionManager, KiteMarketDataService kiteMarketDataService) {
        this.instrumentSubscriptionManager = instrumentSubscriptionManager;
        this.kiteMarketDataService = kiteMarketDataService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }

        switch (command) {
            case SUBSCRIBE -> handleSubscribe(accessor);
            case UNSUBSCRIBE -> handleUnsubscribe(accessor);
            case DISCONNECT -> {
                String sessionId = accessor.getSessionId();
                if (sessionId != null) {
                    handleDisconnect(sessionId);
                }
            }
            default -> {
                // No-op for other commands
            }
        }

        return message;
    }

    /**
     * Handles a STOMP SUBSCRIBE frame. If the destination matches /topic/ticks/{token},
     * registers the token with InstrumentSubscriptionManager and subscribes via Kite.
     */
    private void handleSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(TICK_TOPIC_PREFIX)) {
            return;
        }

        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        if (sessionId == null || subscriptionId == null) {
            return;
        }

        Long token = parseToken(destination);
        if (token == null) {
            return;
        }

        // Track this subscription for later UNSUBSCRIBE lookup
        sessionSubscriptions
                .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(subscriptionId, token);

        String subscriberKey = subscriberKey(sessionId);
        List<Long> newTokens =
                instrumentSubscriptionManager.subscribe(subscriberKey, List.of(token), SubscriptionPriority.MANUAL);

        if (!newTokens.isEmpty()) {
            kiteMarketDataService.subscribe(newTokens);
            log.info("STOMP SUBSCRIBE: session={}, token={} → Kite subscribed", sessionId, token);
        } else {
            log.debug("STOMP SUBSCRIBE: session={}, token={} → already active", sessionId, token);
        }
    }

    /**
     * Handles a STOMP UNSUBSCRIBE frame. Looks up the token from the session mapping
     * (UNSUBSCRIBE only carries subscriptionId, not destination) and unsubscribes.
     */
    private void handleUnsubscribe(StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        if (sessionId == null || subscriptionId == null) {
            return;
        }

        Map<String, Long> subscriptions = sessionSubscriptions.get(sessionId);
        if (subscriptions == null) {
            return;
        }

        Long token = subscriptions.remove(subscriptionId);
        if (token == null) {
            return;
        }

        // Clean up empty session map
        if (subscriptions.isEmpty()) {
            sessionSubscriptions.remove(sessionId);
        }

        String subscriberKey = subscriberKey(sessionId);
        List<Long> removedTokens = instrumentSubscriptionManager.unsubscribe(subscriberKey, List.of(token));

        if (!removedTokens.isEmpty()) {
            kiteMarketDataService.unsubscribe(removedTokens);
            log.info("STOMP UNSUBSCRIBE: session={}, token={} → Kite unsubscribed", sessionId, token);
        } else {
            log.debug("STOMP UNSUBSCRIBE: session={}, token={} → still needed by others", sessionId, token);
        }
    }

    /**
     * Cleans up all subscriptions for a disconnected session.
     * Called from both DISCONNECT frame handling and SessionDisconnectEvent.
     *
     * @param sessionId the STOMP session ID
     */
    public void handleDisconnect(String sessionId) {
        Map<String, Long> subscriptions = sessionSubscriptions.remove(sessionId);
        if (subscriptions == null || subscriptions.isEmpty()) {
            return;
        }

        String subscriberKey = subscriberKey(sessionId);
        List<Long> removedTokens = instrumentSubscriptionManager.unsubscribeAll(subscriberKey);

        if (!removedTokens.isEmpty()) {
            kiteMarketDataService.unsubscribe(removedTokens);
            log.info(
                    "STOMP DISCONNECT: session={}, cleaned up {} subscriptions, {} tokens removed from Kite",
                    sessionId,
                    subscriptions.size(),
                    removedTokens.size());
        }
    }

    /** Extracts the instrument token from /topic/ticks/{token}. */
    private Long parseToken(String destination) {
        try {
            String tokenStr = destination.substring(TICK_TOPIC_PREFIX.length());
            return Long.parseLong(tokenStr);
        } catch (NumberFormatException e) {
            log.warn("Invalid instrument token in STOMP destination: {}", destination);
            return null;
        }
    }

    /** Returns a snapshot of all session→token mappings (for debug endpoint). */
    public Map<String, Map<String, Long>> getSessionSubscriptions() {
        return Map.copyOf(sessionSubscriptions);
    }

    /** Builds the subscriber key for InstrumentSubscriptionManager. */
    private String subscriberKey(String sessionId) {
        return "stomp:" + sessionId;
    }
}
