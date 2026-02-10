package com.algotrader.config;

import com.algotrader.api.websocket.StompTickSubscriptionInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configures the STOMP WebSocket message broker.
 *
 * <p>Registers the {@code /ws} endpoint for STOMP connections, enables simple
 * broker on {@code /topic} and {@code /queue} prefixes, and registers the
 * {@link StompTickSubscriptionInterceptor} on the inbound channel to auto-subscribe
 * Kite instrument tokens when the frontend subscribes to tick topics.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${algotrader.cors.allowed-origin}")
    private String allowedOrigin;

    private final StompTickSubscriptionInterceptor stompTickSubscriptionInterceptor;

    public WebSocketConfig(StompTickSubscriptionInterceptor stompTickSubscriptionInterceptor) {
        this.stompTickSubscriptionInterceptor = stompTickSubscriptionInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigin);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompTickSubscriptionInterceptor);
    }
}
