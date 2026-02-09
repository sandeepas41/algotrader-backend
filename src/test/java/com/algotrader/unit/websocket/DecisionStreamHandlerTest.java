package com.algotrader.unit.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.algotrader.api.websocket.DecisionStreamHandler;
import com.algotrader.api.websocket.WebSocketMessage;
import com.algotrader.domain.enums.DecisionOutcome;
import com.algotrader.domain.enums.DecisionSeverity;
import com.algotrader.domain.enums.DecisionSource;
import com.algotrader.domain.enums.DecisionType;
import com.algotrader.domain.model.DecisionRecord;
import com.algotrader.event.DecisionLogEvent;
import java.time.LocalDate;
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
 * Tests that the DecisionStreamHandler routes DecisionLogEvent messages
 * to the correct STOMP topics with the proper { type, data } envelope.
 */
@ExtendWith(MockitoExtension.class)
class DecisionStreamHandlerTest {

    @Mock
    private SimpMessagingTemplate simpMessagingTemplate;

    private DecisionStreamHandler decisionStreamHandler;

    @BeforeEach
    void setUp() {
        decisionStreamHandler = new DecisionStreamHandler(simpMessagingTemplate);
    }

    private DecisionRecord buildRecord(DecisionSource source, String sourceId) {
        return DecisionRecord.builder()
                .timestamp(LocalDateTime.now())
                .source(source)
                .sourceId(sourceId)
                .decisionType(DecisionType.STRATEGY_ENTRY_EVALUATED)
                .outcome(DecisionOutcome.TRIGGERED)
                .reasoning("Entry conditions met")
                .severity(DecisionSeverity.INFO)
                .sessionDate(LocalDate.now())
                .dataContext(Map.of("spotPrice", 22500))
                .build();
    }

    @Nested
    @DisplayName("Topic routing")
    class TopicRouting {

        @Test
        @DisplayName("sends to /topic/decisions (primary feed)")
        void sendsToMainDecisionsTopic() {
            DecisionRecord record = buildRecord(DecisionSource.STRATEGY_ENGINE, "STR-001");
            DecisionLogEvent event = new DecisionLogEvent(this, record);

            decisionStreamHandler.onDecisionLog(event);

            ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
            verify(simpMessagingTemplate).convertAndSend(eq("/topic/decisions"), captor.capture());

            WebSocketMessage message = captor.getValue();
            assertThat(message.getType()).isEqualTo("DECISION");
            assertThat(message.getData()).isInstanceOf(Map.class);
        }

        @Test
        @DisplayName("sends to source-specific topic /topic/decisions/{source}")
        void sendsToSourceSpecificTopic() {
            DecisionRecord record = buildRecord(DecisionSource.RISK_MANAGER, "RISK-001");
            DecisionLogEvent event = new DecisionLogEvent(this, record);

            decisionStreamHandler.onDecisionLog(event);

            verify(simpMessagingTemplate)
                    .convertAndSend(eq("/topic/decisions/risk_manager"), any(WebSocketMessage.class));
        }

        @Test
        @DisplayName("sends to strategy-specific topic when sourceId starts with STR-")
        void sendsToStrategySpecificTopic() {
            DecisionRecord record = buildRecord(DecisionSource.STRATEGY_ENGINE, "STR-042");
            DecisionLogEvent event = new DecisionLogEvent(this, record);

            decisionStreamHandler.onDecisionLog(event);

            verify(simpMessagingTemplate)
                    .convertAndSend(eq("/topic/decisions/strategy/STR-042"), any(WebSocketMessage.class));
        }

        @Test
        @DisplayName("does not send to strategy-specific topic when sourceId does not start with STR-")
        void doesNotSendToStrategyTopicForNonStrategySource() {
            DecisionRecord record = buildRecord(DecisionSource.RISK_MANAGER, "RISK-001");
            DecisionLogEvent event = new DecisionLogEvent(this, record);

            decisionStreamHandler.onDecisionLog(event);

            verify(simpMessagingTemplate, never())
                    .convertAndSend(eq("/topic/decisions/strategy/RISK-001"), any(WebSocketMessage.class));
        }

        @Test
        @DisplayName("sends to all 3 topics for strategy source with STR- prefix")
        void sendsToAllThreeTopicsForStrategySource() {
            DecisionRecord record = buildRecord(DecisionSource.STRATEGY_ENGINE, "STR-001");
            DecisionLogEvent event = new DecisionLogEvent(this, record);

            decisionStreamHandler.onDecisionLog(event);

            // 3 sends: main, source-specific, strategy-specific
            verify(simpMessagingTemplate, times(3)).convertAndSend(any(String.class), any(WebSocketMessage.class));
        }
    }

    @Nested
    @DisplayName("Payload mapping")
    class PayloadMapping {

        @Test
        @DisplayName("payload contains all decision record fields as flat map")
        @SuppressWarnings("unchecked")
        void payloadContainsAllDecisionRecordFields() {
            DecisionRecord record = DecisionRecord.builder()
                    .timestamp(LocalDateTime.of(2025, 2, 8, 10, 30, 0))
                    .source(DecisionSource.CONDITION_ENGINE)
                    .sourceId("STR-005")
                    .decisionType(DecisionType.CONDITION_EVALUATED)
                    .outcome(DecisionOutcome.SKIPPED)
                    .reasoning("IV too high")
                    .severity(DecisionSeverity.WARNING)
                    .sessionDate(LocalDate.of(2025, 2, 8))
                    .dataContext(Map.of("atmIV", 18.5))
                    .build();

            DecisionLogEvent event = new DecisionLogEvent(this, record);
            decisionStreamHandler.onDecisionLog(event);

            ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
            verify(simpMessagingTemplate).convertAndSend(eq("/topic/decisions"), captor.capture());

            Map<String, Object> payload =
                    (Map<String, Object>) captor.getValue().getData();
            assertThat(payload).containsEntry("source", "CONDITION_ENGINE");
            assertThat(payload).containsEntry("sourceId", "STR-005");
            assertThat(payload).containsEntry("decisionType", "CONDITION_EVALUATED");
            assertThat(payload).containsEntry("outcome", "SKIPPED");
            assertThat(payload).containsEntry("reasoning", "IV too high");
            assertThat(payload).containsEntry("severity", "WARNING");
            assertThat(payload).containsKey("dataContext");
        }

        @Test
        @DisplayName("null source does not cause source-specific topic send")
        void nullSourceSkipsSourceTopic() {
            DecisionRecord record = DecisionRecord.builder()
                    .timestamp(LocalDateTime.now())
                    .source(null)
                    .sourceId(null)
                    .decisionType(DecisionType.KILL_SWITCH_ACTIVATED)
                    .outcome(DecisionOutcome.INFO)
                    .reasoning("System starting")
                    .severity(DecisionSeverity.INFO)
                    .sessionDate(LocalDate.now())
                    .build();

            DecisionLogEvent event = new DecisionLogEvent(this, record);
            decisionStreamHandler.onDecisionLog(event);

            // Only 1 send: main topic (no source-specific, no strategy-specific)
            verify(simpMessagingTemplate, times(1)).convertAndSend(any(String.class), any(WebSocketMessage.class));
        }
    }
}
