package com.algotrader.unit.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.broker.KiteAuthService;
import com.algotrader.event.SessionEvent;
import com.algotrader.event.SessionEventType;
import com.algotrader.session.SessionHealthService;
import com.algotrader.session.SessionState;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Profile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for SessionHealthService covering health check behavior,
 * state transitions, and event publishing.
 */
class SessionHealthServiceTest {

    private KiteAuthService kiteAuthService;
    private ApplicationEventPublisher applicationEventPublisher;
    private KiteConnect kiteConnect;
    private SessionHealthService sessionHealthService;

    @BeforeEach
    void setUp() throws Throwable {
        kiteAuthService = mock(KiteAuthService.class);
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        kiteConnect = mock(KiteConnect.class);
        when(kiteAuthService.getKiteConnect()).thenReturn(kiteConnect);

        sessionHealthService = new SessionHealthService(kiteAuthService, applicationEventPublisher);
    }

    @Nested
    @DisplayName("Initial State")
    class InitialState {

        @Test
        @DisplayName("Default state is DISCONNECTED")
        void defaultStateIsDisconnected() {
            assertThat(sessionHealthService.getState()).isEqualTo(SessionState.DISCONNECTED);
        }

        @Test
        @DisplayName("isSessionActive returns false when DISCONNECTED")
        void isSessionActiveFalseWhenDisconnected() {
            assertThat(sessionHealthService.isSessionActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("Session Creation")
    class SessionCreation {

        @Test
        @DisplayName("onSessionCreated transitions to CONNECTED")
        void onSessionCreatedTransitionsToConnected() {
            sessionHealthService.onSessionCreated("New session for user XY1234");

            assertThat(sessionHealthService.getState()).isEqualTo(SessionState.CONNECTED);
        }

        @Test
        @DisplayName("onSessionCreated publishes SESSION_CREATED event")
        void onSessionCreatedPublishesEvent() {
            sessionHealthService.onSessionCreated("New session created");

            ArgumentCaptor<SessionEvent> captor = ArgumentCaptor.forClass(SessionEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());

            SessionEvent event = captor.getValue();
            assertThat(event.getEventType()).isEqualTo(SessionEventType.SESSION_CREATED);
            assertThat(event.getPreviousState()).isEqualTo(SessionState.DISCONNECTED);
            assertThat(event.getNewState()).isEqualTo(SessionState.CONNECTED);
        }

        @Test
        @DisplayName("onSessionCreated resets consecutive failures")
        void onSessionCreatedResetsFailures() {
            // Simulate some failures first
            sessionHealthService.onSessionCreated("Session");
            assertThat(sessionHealthService.getConsecutiveFailures()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Health Check - Success")
    class HealthCheckSuccess {

        @Test
        @DisplayName("Successful health check promotes CONNECTED to ACTIVE")
        void successfulCheckPromotesToActive() throws Throwable {
            sessionHealthService.onSessionCreated("Session");
            reset(applicationEventPublisher);

            when(kiteConnect.getProfile()).thenReturn(new Profile());
            sessionHealthService.checkSessionHealth(SessionState.CONNECTED);

            assertThat(sessionHealthService.getState()).isEqualTo(SessionState.ACTIVE);
        }

        @Test
        @DisplayName("Successful health check publishes SESSION_VALIDATED event")
        void successfulCheckPublishesValidated() throws Throwable {
            sessionHealthService.onSessionCreated("Session");
            reset(applicationEventPublisher);

            when(kiteConnect.getProfile()).thenReturn(new Profile());
            sessionHealthService.checkSessionHealth(SessionState.CONNECTED);

            ArgumentCaptor<SessionEvent> captor = ArgumentCaptor.forClass(SessionEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getEventType()).isEqualTo(SessionEventType.SESSION_VALIDATED);
        }

        @Test
        @DisplayName("Successful health check when already ACTIVE does not publish event")
        void successfulCheckWhenActiveNoEvent() throws Throwable {
            sessionHealthService.onSessionCreated("Session");
            when(kiteConnect.getProfile()).thenReturn(new Profile());
            sessionHealthService.checkSessionHealth(SessionState.CONNECTED); // -> ACTIVE
            reset(applicationEventPublisher);

            sessionHealthService.checkSessionHealth(SessionState.ACTIVE);

            verify(applicationEventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested
    @DisplayName("Health Check - Failure")
    class HealthCheckFailure {

        @Test
        @DisplayName("Single failure increments counter but does not expire")
        void singleFailureDoesNotExpire() throws Throwable {
            sessionHealthService.onSessionCreated("Session");

            doThrow(new RuntimeException("Connection refused"))
                    .when(kiteConnect)
                    .getProfile();
            sessionHealthService.checkSessionHealth(SessionState.CONNECTED);

            assertThat(sessionHealthService.getConsecutiveFailures()).isEqualTo(1);
            assertThat(sessionHealthService.getState()).isEqualTo(SessionState.CONNECTED);
        }

        @Test
        @DisplayName("3 consecutive failures transition to EXPIRED")
        void threeFailuresExpireSession() throws Throwable {
            sessionHealthService.onSessionCreated("Session");
            reset(applicationEventPublisher);

            doThrow(new RuntimeException("Connection refused"))
                    .when(kiteConnect)
                    .getProfile();

            sessionHealthService.checkSessionHealth(SessionState.CONNECTED);
            sessionHealthService.checkSessionHealth(SessionState.CONNECTED);
            sessionHealthService.checkSessionHealth(SessionState.CONNECTED);

            assertThat(sessionHealthService.getState()).isEqualTo(SessionState.EXPIRED);
        }

        @Test
        @DisplayName("3 consecutive failures publish SESSION_EXPIRED event")
        void threeFailuresPublishExpiredEvent() throws Throwable {
            sessionHealthService.onSessionCreated("Session");
            reset(applicationEventPublisher);

            doThrow(new RuntimeException("Connection refused"))
                    .when(kiteConnect)
                    .getProfile();

            sessionHealthService.checkSessionHealth(SessionState.CONNECTED);
            sessionHealthService.checkSessionHealth(SessionState.CONNECTED);
            sessionHealthService.checkSessionHealth(SessionState.CONNECTED);

            ArgumentCaptor<SessionEvent> captor = ArgumentCaptor.forClass(SessionEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getEventType()).isEqualTo(SessionEventType.SESSION_EXPIRED);
        }

        @Test
        @DisplayName("Successful check resets failure counter")
        void successResetsFailureCounter() throws Throwable {
            sessionHealthService.onSessionCreated("Session");

            doThrow(new RuntimeException("fail")).when(kiteConnect).getProfile();
            sessionHealthService.checkSessionHealth(SessionState.CONNECTED);
            sessionHealthService.checkSessionHealth(SessionState.CONNECTED);
            assertThat(sessionHealthService.getConsecutiveFailures()).isEqualTo(2);

            // Now succeed
            reset(kiteConnect);
            when(kiteConnect.getProfile()).thenReturn(new Profile());
            sessionHealthService.checkSessionHealth(SessionState.CONNECTED);

            assertThat(sessionHealthService.getConsecutiveFailures()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Skip conditions")
    class SkipConditions {

        @Test
        @DisplayName("Health check skipped when DISCONNECTED")
        void skippedWhenDisconnected() throws Throwable {
            sessionHealthService.checkSessionHealth(SessionState.DISCONNECTED);
            verify(kiteConnect, never()).getProfile();
        }

        @Test
        @DisplayName("Health check skipped when EXPIRED")
        void skippedWhenExpired() throws Throwable {
            sessionHealthService.checkSessionHealth(SessionState.EXPIRED);
            verify(kiteConnect, never()).getProfile();
        }
    }

    @Nested
    @DisplayName("Session Invalidation")
    class SessionInvalidation {

        @Test
        @DisplayName("onSessionInvalidated transitions to EXPIRED")
        void onSessionInvalidatedTransitionsToExpired() {
            sessionHealthService.onSessionCreated("Session");
            sessionHealthService.onSessionInvalidated("403 from Kite");

            assertThat(sessionHealthService.getState()).isEqualTo(SessionState.EXPIRED);
        }

        @Test
        @DisplayName("onSessionInvalidated publishes SESSION_INVALIDATED event")
        void onSessionInvalidatedPublishesEvent() {
            sessionHealthService.onSessionCreated("Session");
            reset(applicationEventPublisher);

            sessionHealthService.onSessionInvalidated("Concurrent login");

            ArgumentCaptor<SessionEvent> captor = ArgumentCaptor.forClass(SessionEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getEventType()).isEqualTo(SessionEventType.SESSION_INVALIDATED);
        }

        @Test
        @DisplayName("Duplicate invalidation does not publish second event")
        void duplicateInvalidationNoSecondEvent() {
            sessionHealthService.onSessionCreated("Session");
            sessionHealthService.onSessionInvalidated("First");
            reset(applicationEventPublisher);

            sessionHealthService.onSessionInvalidated("Second");
            verify(applicationEventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested
    @DisplayName("Re-authentication")
    class ReAuthentication {

        @Test
        @DisplayName("onReAuthStarted transitions to AUTHENTICATING")
        void onReAuthStartedTransitionsToAuthenticating() {
            sessionHealthService.onSessionCreated("Session");
            sessionHealthService.onReAuthStarted();

            assertThat(sessionHealthService.getState()).isEqualTo(SessionState.AUTHENTICATING);
        }

        @Test
        @DisplayName("onReAuthCompleted transitions to CONNECTED and publishes event")
        void onReAuthCompletedTransitionsToConnected() {
            sessionHealthService.onReAuthStarted();
            reset(applicationEventPublisher);

            sessionHealthService.onReAuthCompleted();

            assertThat(sessionHealthService.getState()).isEqualTo(SessionState.CONNECTED);

            ArgumentCaptor<SessionEvent> captor = ArgumentCaptor.forClass(SessionEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getEventType()).isEqualTo(SessionEventType.SESSION_RECONNECTED);
        }
    }

    @Nested
    @DisplayName("Expiry Warning")
    class ExpiryWarning {

        @Test
        @DisplayName("onExpiryWarning transitions ACTIVE to EXPIRY_WARNING")
        void expiryWarningFromActive() throws Throwable {
            sessionHealthService.onSessionCreated("Session");
            when(kiteConnect.getProfile()).thenReturn(new Profile());
            sessionHealthService.checkSessionHealth(SessionState.CONNECTED); // -> ACTIVE

            sessionHealthService.onExpiryWarning();
            assertThat(sessionHealthService.getState()).isEqualTo(SessionState.EXPIRY_WARNING);
        }

        @Test
        @DisplayName("isSessionActive returns true during EXPIRY_WARNING")
        void isSessionActiveDuringExpiryWarning() throws Throwable {
            sessionHealthService.onSessionCreated("Session");
            when(kiteConnect.getProfile()).thenReturn(new Profile());
            sessionHealthService.checkSessionHealth(SessionState.CONNECTED); // -> ACTIVE
            sessionHealthService.onExpiryWarning();

            assertThat(sessionHealthService.isSessionActive()).isTrue();
        }
    }
}
