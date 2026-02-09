package com.algotrader.unit.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.api.websocket.WebSocketMessage;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the standard WebSocket message envelope contract: { type, data }.
 */
class WebSocketMessageTest {

    @Test
    @DisplayName("of() factory creates message with correct type and data")
    void ofFactoryCreatesMessageWithTypeAndData() {
        Map<String, Object> data = Map.of("key", "value");
        WebSocketMessage message = WebSocketMessage.of("ORDER", data);

        assertThat(message.getType()).isEqualTo("ORDER");
        assertThat(message.getData()).isEqualTo(data);
    }

    @Test
    @DisplayName("builder creates message with correct type and data")
    void builderCreatesMessageWithTypeAndData() {
        WebSocketMessage message = WebSocketMessage.builder()
                .type("TICK")
                .data(Map.of("lastPrice", 100.5))
                .build();

        assertThat(message.getType()).isEqualTo("TICK");
        assertThat(message.getData()).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("no-arg constructor creates message with null fields")
    void noArgConstructorCreatesEmptyMessage() {
        WebSocketMessage message = new WebSocketMessage();

        assertThat(message.getType()).isNull();
        assertThat(message.getData()).isNull();
    }

    @Test
    @DisplayName("data can hold any object type (polymorphic payload)")
    void dataCanHoldAnyObjectType() {
        WebSocketMessage withMap = WebSocketMessage.of("TEST", Map.of("a", 1));
        assertThat(withMap.getData()).isInstanceOf(Map.class);

        WebSocketMessage withString = WebSocketMessage.of("TEST", "simple string");
        assertThat(withString.getData()).isInstanceOf(String.class);

        WebSocketMessage withNull = WebSocketMessage.of("TEST", null);
        assertThat(withNull.getData()).isNull();
    }

    @Test
    @DisplayName("type field distinguishes message kinds")
    void typeFieldDistinguishesMessageKinds() {
        WebSocketMessage order = WebSocketMessage.of("ORDER", Map.of());
        WebSocketMessage tick = WebSocketMessage.of("TICK", Map.of());
        WebSocketMessage decision = WebSocketMessage.of("DECISION", Map.of());

        assertThat(order.getType()).isNotEqualTo(tick.getType());
        assertThat(tick.getType()).isNotEqualTo(decision.getType());
    }
}
