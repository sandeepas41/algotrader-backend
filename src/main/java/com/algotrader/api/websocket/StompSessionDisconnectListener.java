package com.algotrader.api.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Listens for STOMP session disconnect events to clean up tick subscriptions.
 *
 * <p>This handles abrupt disconnections (browser tab close, network drop) where
 * a STOMP DISCONNECT frame is never sent. Spring fires {@link SessionDisconnectEvent}
 * in both graceful and abrupt cases, so this ensures cleanup always happens.
 *
 * <p>Delegates to {@link StompTickSubscriptionInterceptor#handleDisconnect(String)}
 * which is idempotent — safe to call even if the interceptor already handled the
 * graceful DISCONNECT frame.
 */
@Component
public class StompSessionDisconnectListener {

    private static final Logger log = LoggerFactory.getLogger(StompSessionDisconnectListener.class);

    private final StompTickSubscriptionInterceptor stompTickSubscriptionInterceptor;

    public StompSessionDisconnectListener(StompTickSubscriptionInterceptor stompTickSubscriptionInterceptor) {
        this.stompTickSubscriptionInterceptor = stompTickSubscriptionInterceptor;
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        log.debug("STOMP session disconnected: {}", sessionId);
        stompTickSubscriptionInterceptor.handleDisconnect(sessionId);
    }
}
