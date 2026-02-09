package com.algotrader.unit.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.algotrader.broker.KiteAuthService;
import com.algotrader.session.SessionExpiryService;
import com.algotrader.session.SessionHealthService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SessionExpiryService covering expiry warnings at 30-min
 * and 5-min marks, and 403 auto-reauth behavior.
 */
class SessionExpiryServiceTest {

    private SessionHealthService sessionHealthService;
    private KiteAuthService kiteAuthService;
    private SessionExpiryService sessionExpiryService;

    @BeforeEach
    void setUp() {
        sessionHealthService = mock(SessionHealthService.class);
        kiteAuthService = mock(KiteAuthService.class);
        sessionExpiryService = new SessionExpiryService(sessionHealthService, kiteAuthService);
    }

    @Nested
    @DisplayName("30-Minute Expiry Warning")
    class ThirtyMinuteWarning {

        @Test
        @DisplayName("Fires 30-min warning when 30 minutes remain")
        void firesAt30Minutes() {
            LocalDateTime expiry = LocalDateTime.of(2025, 6, 11, 6, 0);
            LocalDateTime now = LocalDateTime.of(2025, 6, 11, 5, 30);

            sessionExpiryService.checkExpiry(expiry, now);

            verify(sessionHealthService).onExpiryWarning();
            assertThat(sessionExpiryService.isThirtyMinWarningFired()).isTrue();
        }

        @Test
        @DisplayName("Fires 30-min warning when 20 minutes remain")
        void firesAt20Minutes() {
            LocalDateTime expiry = LocalDateTime.of(2025, 6, 11, 6, 0);
            LocalDateTime now = LocalDateTime.of(2025, 6, 11, 5, 40);

            sessionExpiryService.checkExpiry(expiry, now);

            verify(sessionHealthService).onExpiryWarning();
        }

        @Test
        @DisplayName("Does not fire 30-min warning when 31 minutes remain")
        void doesNotFireAt31Minutes() {
            LocalDateTime expiry = LocalDateTime.of(2025, 6, 11, 6, 0);
            LocalDateTime now = LocalDateTime.of(2025, 6, 11, 5, 29);

            sessionExpiryService.checkExpiry(expiry, now);

            verify(sessionHealthService, never()).onExpiryWarning();
            assertThat(sessionExpiryService.isThirtyMinWarningFired()).isFalse();
        }

        @Test
        @DisplayName("30-min warning fires only once")
        void firesOnlyOnce() {
            LocalDateTime expiry = LocalDateTime.of(2025, 6, 11, 6, 0);

            sessionExpiryService.checkExpiry(expiry, LocalDateTime.of(2025, 6, 11, 5, 30));
            sessionExpiryService.checkExpiry(expiry, LocalDateTime.of(2025, 6, 11, 5, 35));

            // onExpiryWarning should only be called once
            verify(sessionHealthService).onExpiryWarning();
        }
    }

    @Nested
    @DisplayName("5-Minute Expiry Warning")
    class FiveMinuteWarning {

        @Test
        @DisplayName("Fires 5-min warning when 5 minutes remain")
        void firesAt5Minutes() {
            LocalDateTime expiry = LocalDateTime.of(2025, 6, 11, 6, 0);
            LocalDateTime now = LocalDateTime.of(2025, 6, 11, 5, 55);

            sessionExpiryService.checkExpiry(expiry, now);

            assertThat(sessionExpiryService.isFiveMinWarningFired()).isTrue();
        }

        @Test
        @DisplayName("Does not fire 5-min warning when 6 minutes remain")
        void doesNotFireAt6Minutes() {
            LocalDateTime expiry = LocalDateTime.of(2025, 6, 11, 6, 0);
            LocalDateTime now = LocalDateTime.of(2025, 6, 11, 5, 54);

            sessionExpiryService.checkExpiry(expiry, now);

            assertThat(sessionExpiryService.isFiveMinWarningFired()).isFalse();
        }

        @Test
        @DisplayName("5-min warning fires only once")
        void firesOnlyOnce() {
            LocalDateTime expiry = LocalDateTime.of(2025, 6, 11, 6, 0);

            sessionExpiryService.checkExpiry(expiry, LocalDateTime.of(2025, 6, 11, 5, 55));
            sessionExpiryService.checkExpiry(expiry, LocalDateTime.of(2025, 6, 11, 5, 57));

            assertThat(sessionExpiryService.isFiveMinWarningFired()).isTrue();
        }
    }

    @Nested
    @DisplayName("Session Expired")
    class SessionExpired {

        @Test
        @DisplayName("Marks session as expired when time is past expiry")
        void expiresWhenTimePastExpiry() {
            LocalDateTime expiry = LocalDateTime.of(2025, 6, 11, 6, 0);
            LocalDateTime now = LocalDateTime.of(2025, 6, 11, 6, 1);

            sessionExpiryService.checkExpiry(expiry, now);

            verify(sessionHealthService).handleSessionExpiry("Session TTL expired");
        }

        @Test
        @DisplayName("Marks session as expired when time equals expiry")
        void expiresWhenTimeEqualsExpiry() {
            LocalDateTime expiry = LocalDateTime.of(2025, 6, 11, 6, 0);

            sessionExpiryService.checkExpiry(expiry, expiry);

            verify(sessionHealthService).handleSessionExpiry("Session TTL expired");
        }

        @Test
        @DisplayName("Resets warning flags after expiry")
        void resetsWarningFlagsAfterExpiry() {
            LocalDateTime expiry = LocalDateTime.of(2025, 6, 11, 6, 0);

            // First fire warnings
            sessionExpiryService.checkExpiry(expiry, LocalDateTime.of(2025, 6, 11, 5, 55));
            assertThat(sessionExpiryService.isThirtyMinWarningFired()).isTrue();
            assertThat(sessionExpiryService.isFiveMinWarningFired()).isTrue();

            // Then expire
            sessionExpiryService.checkExpiry(expiry, LocalDateTime.of(2025, 6, 11, 6, 1));

            // Flags should be reset for the next session
            assertThat(sessionExpiryService.isThirtyMinWarningFired()).isFalse();
            assertThat(sessionExpiryService.isFiveMinWarningFired()).isFalse();
        }
    }

    @Nested
    @DisplayName("403 Auto-Reauth")
    class AutoReauth {

        @Test
        @DisplayName("onTokenException triggers reauth flow")
        void onTokenExceptionTriggersReauth() {
            sessionExpiryService.onTokenException();

            verify(sessionHealthService).onSessionInvalidated(org.mockito.ArgumentMatchers.anyString());
            verify(sessionHealthService).onReAuthStarted();
            verify(kiteAuthService).reAuthenticate();
            verify(sessionHealthService).onReAuthCompleted();
        }

        @Test
        @DisplayName("onTokenException resets warning flags on success")
        void onTokenExceptionResetsFlags() {
            // Pre-set warning flags
            sessionExpiryService.checkExpiry(LocalDateTime.of(2025, 6, 11, 6, 0), LocalDateTime.of(2025, 6, 11, 5, 55));
            assertThat(sessionExpiryService.isThirtyMinWarningFired()).isTrue();

            sessionExpiryService.onTokenException();

            assertThat(sessionExpiryService.isThirtyMinWarningFired()).isFalse();
            assertThat(sessionExpiryService.isFiveMinWarningFired()).isFalse();
        }

        @Test
        @DisplayName("onTokenException handles reauth failure gracefully")
        void onTokenExceptionHandlesFailure() {
            doThrow(new RuntimeException("Sidecar down")).when(kiteAuthService).reAuthenticate();

            sessionExpiryService.onTokenException();

            verify(sessionHealthService).handleSessionExpiry(org.mockito.ArgumentMatchers.contains("Sidecar down"));
        }
    }

    @Nested
    @DisplayName("Warning Flag Reset")
    class WarningFlagReset {

        @Test
        @DisplayName("resetWarningFlags clears both flags")
        void resetClearsBothFlags() {
            LocalDateTime expiry = LocalDateTime.of(2025, 6, 11, 6, 0);
            sessionExpiryService.checkExpiry(expiry, LocalDateTime.of(2025, 6, 11, 5, 55));

            sessionExpiryService.resetWarningFlags();

            assertThat(sessionExpiryService.isThirtyMinWarningFired()).isFalse();
            assertThat(sessionExpiryService.isFiveMinWarningFired()).isFalse();
        }
    }
}
