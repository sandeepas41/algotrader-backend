package com.algotrader.unit.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.algotrader.exception.SessionExpiredException;
import com.algotrader.session.DegradationLevel;
import com.algotrader.session.SessionGuard;
import com.algotrader.session.SessionHealthService;
import com.algotrader.session.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SessionGuard covering session gating and degradation level mapping.
 */
class SessionGuardTest {

    private SessionHealthService sessionHealthService;
    private SessionGuard sessionGuard;

    @BeforeEach
    void setUp() {
        sessionHealthService = mock(SessionHealthService.class);
        sessionGuard = new SessionGuard(sessionHealthService);
    }

    @Nested
    @DisplayName("requireActiveSession")
    class RequireActiveSession {

        @Test
        @DisplayName("Does not throw when session is ACTIVE")
        void doesNotThrowWhenActive() {
            when(sessionHealthService.isSessionActive()).thenReturn(true);
            sessionGuard.requireActiveSession(); // Should not throw
        }

        @Test
        @DisplayName("Throws SessionExpiredException when session is DISCONNECTED")
        void throwsWhenDisconnected() {
            when(sessionHealthService.isSessionActive()).thenReturn(false);
            when(sessionHealthService.getState()).thenReturn(SessionState.DISCONNECTED);

            assertThatThrownBy(() -> sessionGuard.requireActiveSession())
                    .isInstanceOf(SessionExpiredException.class)
                    .hasMessageContaining("DISCONNECTED")
                    .hasMessageContaining("re-authenticate");
        }

        @Test
        @DisplayName("Throws SessionExpiredException when session is EXPIRED")
        void throwsWhenExpired() {
            when(sessionHealthService.isSessionActive()).thenReturn(false);
            when(sessionHealthService.getState()).thenReturn(SessionState.EXPIRED);

            assertThatThrownBy(() -> sessionGuard.requireActiveSession())
                    .isInstanceOf(SessionExpiredException.class)
                    .hasMessageContaining("EXPIRED");
        }
    }

    @Nested
    @DisplayName("getDegradationLevel")
    class GetDegradationLevel {

        @Test
        @DisplayName("NONE when ACTIVE")
        void noneWhenActive() {
            when(sessionHealthService.getState()).thenReturn(SessionState.ACTIVE);
            assertThat(sessionGuard.getDegradationLevel()).isEqualTo(DegradationLevel.NONE);
        }

        @Test
        @DisplayName("NONE when CONNECTED")
        void noneWhenConnected() {
            when(sessionHealthService.getState()).thenReturn(SessionState.CONNECTED);
            assertThat(sessionGuard.getDegradationLevel()).isEqualTo(DegradationLevel.NONE);
        }

        @Test
        @DisplayName("WARNING when EXPIRY_WARNING")
        void warningWhenExpiryWarning() {
            when(sessionHealthService.getState()).thenReturn(SessionState.EXPIRY_WARNING);
            assertThat(sessionGuard.getDegradationLevel()).isEqualTo(DegradationLevel.WARNING);
        }

        @Test
        @DisplayName("PARTIAL when AUTHENTICATING")
        void partialWhenAuthenticating() {
            when(sessionHealthService.getState()).thenReturn(SessionState.AUTHENTICATING);
            assertThat(sessionGuard.getDegradationLevel()).isEqualTo(DegradationLevel.PARTIAL);
        }

        @Test
        @DisplayName("DEGRADED when EXPIRED")
        void degradedWhenExpired() {
            when(sessionHealthService.getState()).thenReturn(SessionState.EXPIRED);
            assertThat(sessionGuard.getDegradationLevel()).isEqualTo(DegradationLevel.DEGRADED);
        }

        @Test
        @DisplayName("DEGRADED when DISCONNECTED")
        void degradedWhenDisconnected() {
            when(sessionHealthService.getState()).thenReturn(SessionState.DISCONNECTED);
            assertThat(sessionGuard.getDegradationLevel()).isEqualTo(DegradationLevel.DEGRADED);
        }
    }

    @Nested
    @DisplayName("isLiveTradingAllowed")
    class IsLiveTradingAllowed {

        @Test
        @DisplayName("Returns true when session is active")
        void trueWhenActive() {
            when(sessionHealthService.isSessionActive()).thenReturn(true);
            assertThat(sessionGuard.isLiveTradingAllowed()).isTrue();
        }

        @Test
        @DisplayName("Returns false when session is not active")
        void falseWhenNotActive() {
            when(sessionHealthService.isSessionActive()).thenReturn(false);
            assertThat(sessionGuard.isLiveTradingAllowed()).isFalse();
        }
    }
}
