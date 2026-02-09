package com.algotrader.unit.broker;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.broker.KiteMarketDataService;
import com.algotrader.config.KiteConfig;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link KiteMarketDataService}.
 *
 * <p>Tests reconnection backoff calculation and DEGRADED state detection.
 * Actual WebSocket connection is not tested here (requires integration test)
 * because KiteTicker requires a real Kite API key and opens a socket.
 *
 * <p>Uses reflection to access package-private computeReconnectDelay() for testing
 * the exponential backoff algorithm without exposing it as public API.
 */
@ExtendWith(MockitoExtension.class)
class KiteMarketDataServiceTest {

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private KiteConfig kiteConfig;
    private KiteMarketDataService kiteMarketDataService;

    @BeforeEach
    void setUp() {
        kiteConfig = new KiteConfig();
        kiteConfig.setApiKey("test_key");
        kiteConfig.setApiSecret("test_secret");
        kiteMarketDataService = new KiteMarketDataService(kiteConfig, applicationEventPublisher);
    }

    @Test
    @DisplayName("reconnect delay: doubles exponentially from 1s")
    void reconnectDelayDoublesExponentially() throws Exception {
        assertThat(invokeComputeDelay(1)).isEqualTo(1000L);
        assertThat(invokeComputeDelay(2)).isEqualTo(2000L);
        assertThat(invokeComputeDelay(3)).isEqualTo(4000L);
        assertThat(invokeComputeDelay(4)).isEqualTo(8000L);
        assertThat(invokeComputeDelay(5)).isEqualTo(16000L);
    }

    @Test
    @DisplayName("reconnect delay: caps at 30s max")
    void reconnectDelayCapsAtMax() throws Exception {
        // 2^15 * 1000 = 32768ms, should cap at 30000ms
        assertThat(invokeComputeDelay(16)).isEqualTo(30_000L);

        // Even higher attempts stay capped
        assertThat(invokeComputeDelay(20)).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("initial state: not connected, not degraded")
    void initialState() {
        assertThat(kiteMarketDataService.isConnected()).isFalse();
        assertThat(kiteMarketDataService.isDegraded()).isFalse();
        assertThat(kiteMarketDataService.getSubscribedCount()).isZero();
    }

    @Test
    @DisplayName("getSubscribedTokens: returns empty set initially")
    void subscribedTokensEmpty() {
        assertThat(kiteMarketDataService.getSubscribedTokens()).isEmpty();
    }

    /**
     * Invokes the package-private computeReconnectDelay via reflection.
     * This method is a pure function testing exponential backoff math.
     */
    private long invokeComputeDelay(int attempt) throws Exception {
        Method method = KiteMarketDataService.class.getDeclaredMethod("computeReconnectDelay", int.class);
        method.setAccessible(true);
        return (long) method.invoke(kiteMarketDataService, attempt);
    }
}
