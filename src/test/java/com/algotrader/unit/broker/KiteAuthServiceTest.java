package com.algotrader.unit.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.broker.KiteAuthService;
import com.algotrader.broker.KiteLoginAutomation;
import com.algotrader.config.KiteConfig;
import com.algotrader.entity.KiteSessionEntity;
import com.algotrader.exception.BrokerException;
import com.algotrader.repository.jpa.KiteSessionJpaRepository;
import com.algotrader.repository.redis.KiteSessionRedisRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link KiteAuthService}.
 *
 * <p>Tests login URL generation, token persistence, token expiry validation,
 * and the single-flight reauth gate (concurrent threads). Repositories are mocked;
 * the KiteConnect bean is a real instance (same as KiteConfig produces) so we can
 * verify setAccessToken/setUserId are called correctly.
 */
@ExtendWith(MockitoExtension.class)
class KiteAuthServiceTest {

    @Mock
    private KiteSessionJpaRepository kiteSessionJpaRepository;

    @Mock
    private KiteSessionRedisRepository kiteSessionRedisRepository;

    @Mock
    private KiteLoginAutomation kiteLoginAutomation;

    private KiteConfig kiteConfig;
    private KiteConnect kiteConnect;
    private KiteAuthService kiteAuthService;

    @BeforeEach
    void setUp() {
        kiteConfig = new KiteConfig();
        kiteConfig.setApiKey("test-api-key");
        kiteConfig.setApiSecret("test-api-secret");

        KiteConfig.AutoLogin autoLogin = new KiteConfig.AutoLogin();
        autoLogin.setEnabled(false);
        kiteConfig.setAutoLogin(autoLogin);

        // Real KiteConnect bean (same as KiteConfig.kiteConnect() would produce)
        kiteConnect = new KiteConnect("test-api-key");

        kiteAuthService = new KiteAuthService(
                kiteConfig, kiteConnect, kiteSessionJpaRepository, kiteSessionRedisRepository, kiteLoginAutomation);
    }

    @Test
    @DisplayName("getLoginUrl returns a valid Kite login URL containing the API key")
    void getLoginUrl_returnsUrlWithApiKey() {
        String loginUrl = kiteAuthService.getLoginUrl();

        assertThat(loginUrl).isNotNull();
        assertThat(loginUrl).contains("test-api-key");
        assertThat(loginUrl).startsWith("https://");
    }

    @Test
    @DisplayName("isAuthenticated returns false when no token is set")
    void isAuthenticated_returnsFalse_whenNoToken() {
        assertThat(kiteAuthService.isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("acquireTokenOnStartup reuses valid token from H2 and configures KiteConnect bean")
    void acquireTokenOnStartup_reusesValidH2Token() {
        LocalDateTime futureExpiry = LocalDateTime.now().plusHours(10);
        KiteSessionEntity validSession = KiteSessionEntity.builder()
                .id("session-1")
                .userId("AB1234")
                .accessToken("valid-access-token")
                .userName("Test User")
                .loginTime(LocalDateTime.now().minusHours(1))
                .expiresAt(futureExpiry)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();

        when(kiteSessionJpaRepository.findAll()).thenReturn(List.of(validSession));
        when(kiteSessionRedisRepository.hasSession("AB1234")).thenReturn(false);

        kiteAuthService.acquireTokenOnStartup();

        assertThat(kiteAuthService.isAuthenticated()).isTrue();
        assertThat(kiteAuthService.getAccessToken()).isEqualTo("valid-access-token");
        assertThat(kiteAuthService.getCurrentUserId()).isEqualTo("AB1234");
        assertThat(kiteAuthService.getCurrentUserName()).isEqualTo("Test User");

        // Verify the shared KiteConnect bean was configured
        assertThat(kiteConnect.getAccessToken()).isEqualTo("valid-access-token");
        assertThat(kiteConnect.getUserId()).isEqualTo("AB1234");

        // Should cache in Redis since it wasn't there
        verify(kiteSessionRedisRepository).storeSession("AB1234", "valid-access-token", "Test User");
    }

    @Test
    @DisplayName("acquireTokenOnStartup skips Redis store if session already cached")
    void acquireTokenOnStartup_skipsRedis_whenAlreadyCached() {
        LocalDateTime futureExpiry = LocalDateTime.now().plusHours(10);
        KiteSessionEntity validSession = KiteSessionEntity.builder()
                .id("session-1")
                .userId("AB1234")
                .accessToken("valid-access-token")
                .userName("Test User")
                .loginTime(LocalDateTime.now().minusHours(1))
                .expiresAt(futureExpiry)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();

        when(kiteSessionJpaRepository.findAll()).thenReturn(List.of(validSession));
        when(kiteSessionRedisRepository.hasSession("AB1234")).thenReturn(true);

        kiteAuthService.acquireTokenOnStartup();

        assertThat(kiteAuthService.isAuthenticated()).isTrue();
        verify(kiteSessionRedisRepository, never()).storeSession(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("acquireTokenOnStartup ignores expired tokens in H2")
    void acquireTokenOnStartup_ignoresExpiredH2Token() {
        KiteSessionEntity expiredSession = KiteSessionEntity.builder()
                .id("session-old")
                .userId("AB1234")
                .accessToken("expired-token")
                .userName("Test User")
                .loginTime(LocalDateTime.now().minusDays(2))
                .expiresAt(LocalDateTime.now().minusHours(5))
                .createdAt(LocalDateTime.now().minusDays(2))
                .build();

        when(kiteSessionJpaRepository.findAll()).thenReturn(List.of(expiredSession));

        kiteAuthService.acquireTokenOnStartup();

        assertThat(kiteAuthService.isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("acquireTokenOnStartup starts degraded mode when H2 is empty and auto-login disabled")
    void acquireTokenOnStartup_degradedMode_whenNoTokenAndAutoLoginDisabled() {
        when(kiteSessionJpaRepository.findAll()).thenReturn(List.of());

        kiteAuthService.acquireTokenOnStartup();

        assertThat(kiteAuthService.isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("reAuthenticate with auto-login disabled throws BrokerException")
    void reAuthenticate_throwsWhenAutoLoginDisabled() {
        assertThatThrownBy(() -> kiteAuthService.reAuthenticate()).isInstanceOf(BrokerException.class);
    }

    @Test
    @DisplayName("Token expiry: valid session has correct expiry and is authenticated")
    void tokenExpiryCheck_validVsExpired() {
        LocalDateTime futureExpiry = LocalDateTime.now().plusHours(10);
        KiteSessionEntity validSession = KiteSessionEntity.builder()
                .id("session-1")
                .userId("AB1234")
                .accessToken("good-token")
                .userName("Test User")
                .loginTime(LocalDateTime.now())
                .expiresAt(futureExpiry)
                .createdAt(LocalDateTime.now())
                .build();

        when(kiteSessionJpaRepository.findAll()).thenReturn(List.of(validSession));
        when(kiteSessionRedisRepository.hasSession("AB1234")).thenReturn(true);

        kiteAuthService.acquireTokenOnStartup();
        assertThat(kiteAuthService.isAuthenticated()).isTrue();
        assertThat(kiteAuthService.getTokenExpiry()).isEqualTo(futureExpiry);
    }

    @Test
    @DisplayName("Single-flight reauth gate: 5 concurrent threads, only 1 triggers reauth")
    void singleFlightReauthGate_onlyOneThreadTriggersReauth() throws Exception {
        // Enable auto-login so reAuthenticate() attempts the Playwright login path
        kiteConfig.getAutoLogin().setEnabled(true);

        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger reauthErrors = new AtomicInteger(0);
        AtomicInteger reauthSuccess = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    kiteAuthService.reAuthenticate();
                    reauthSuccess.incrementAndGet();
                } catch (Exception e) {
                    // Only the thread that actually calls autoLogin gets the error;
                    // other threads wait on the lock and return without error
                    reauthErrors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // Exactly 1 thread should have attempted autoLogin and failed (Playwright not available in test).
        // The remaining threads waited on the lock and returned without error.
        assertThat(reauthErrors.get()).isEqualTo(1);
        assertThat(reauthSuccess.get()).isEqualTo(threadCount - 1);
        // All threads completed -- no deadlock
        assertThat(reauthErrors.get() + reauthSuccess.get()).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("getKiteConnect returns the shared KiteConnect bean")
    void getKiteConnect_returnsSharedBean() {
        assertThat(kiteAuthService.getKiteConnect()).isSameAs(kiteConnect);
    }

    @Test
    @DisplayName("getAccessToken returns null when not authenticated")
    void getAccessToken_returnsNull_whenNotAuthenticated() {
        assertThat(kiteAuthService.getAccessToken()).isNull();
    }

    @Test
    @DisplayName("logout clears in-memory state and deletes sessions from H2 and Redis")
    void logout_clearsStateAndDeletesSessions() {
        // First, set up an authenticated state via acquireTokenOnStartup
        LocalDateTime futureExpiry = LocalDateTime.now().plusHours(10);
        KiteSessionEntity validSession = KiteSessionEntity.builder()
                .id("session-1")
                .userId("AB1234")
                .accessToken("valid-access-token")
                .userName("Test User")
                .loginTime(LocalDateTime.now().minusHours(1))
                .expiresAt(futureExpiry)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();

        when(kiteSessionJpaRepository.findAll()).thenReturn(List.of(validSession));
        when(kiteSessionRedisRepository.hasSession("AB1234")).thenReturn(true);
        kiteAuthService.acquireTokenOnStartup();
        assertThat(kiteAuthService.isAuthenticated()).isTrue();

        // Now logout
        kiteAuthService.logout();

        assertThat(kiteAuthService.isAuthenticated()).isFalse();
        assertThat(kiteAuthService.getAccessToken()).isNull();
        assertThat(kiteAuthService.getCurrentUserId()).isNull();
        assertThat(kiteAuthService.getCurrentUserName()).isNull();
        assertThat(kiteAuthService.getTokenExpiry()).isNull();

        // Verify persistence was cleaned up
        verify(kiteSessionJpaRepository).deleteAll();
        verify(kiteSessionRedisRepository).deleteSession("AB1234");
    }

    @Test
    @DisplayName("logout when not authenticated still clears persistence stores")
    void logout_whenNotAuthenticated_stillClearsStores() {
        assertThat(kiteAuthService.isAuthenticated()).isFalse();

        kiteAuthService.logout();

        assertThat(kiteAuthService.isAuthenticated()).isFalse();
        verify(kiteSessionJpaRepository).deleteAll();
    }
}
