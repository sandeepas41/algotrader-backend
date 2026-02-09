package com.algotrader.unit.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.broker.KiteMarketDataService;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.observability.DecisionLogger;
import com.algotrader.recovery.GracefulShutdownService;
import com.algotrader.recovery.ShutdownMessage;
import com.algotrader.risk.AccountRiskChecker;
import com.algotrader.risk.KillSwitchService;
import com.algotrader.service.DataSyncService;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Tests for GracefulShutdownService verifying the shutdown sequence:
 * frontend notification, strategy pause, data flush, state persistence,
 * and WebSocket disconnect -- all tested through the public stop() API.
 */
@ExtendWith(MockitoExtension.class)
class GracefulShutdownServiceTest {

    @Mock
    private DataSyncService dataSyncService;

    @Mock
    private AccountRiskChecker accountRiskChecker;

    @Mock
    private StrategyEngine strategyEngine;

    @Mock
    private KillSwitchService killSwitchService;

    @Mock
    private KiteMarketDataService kiteMarketDataService;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SimpMessagingTemplate simpMessagingTemplate;

    @Mock
    private DecisionLogger decisionLogger;

    private GracefulShutdownService gracefulShutdownService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        gracefulShutdownService = new GracefulShutdownService(
                dataSyncService,
                accountRiskChecker,
                strategyEngine,
                killSwitchService,
                kiteMarketDataService,
                redisTemplate,
                simpMessagingTemplate,
                decisionLogger);
    }

    @Test
    @DisplayName("start() sets running to true, stop() sets it to false")
    void startAndStop_togglesRunning() {
        gracefulShutdownService.start();
        assertThat(gracefulShutdownService.isRunning()).isTrue();

        when(accountRiskChecker.getDailyRealisedPnl()).thenReturn(BigDecimal.ZERO);
        when(killSwitchService.isActive()).thenReturn(false);

        gracefulShutdownService.stop();
        assertThat(gracefulShutdownService.isRunning()).isFalse();
    }

    @Test
    @DisplayName("stop() notifies frontend via WebSocket")
    void stop_notifiesFrontend() {
        when(accountRiskChecker.getDailyRealisedPnl()).thenReturn(BigDecimal.ZERO);
        when(killSwitchService.isActive()).thenReturn(false);

        gracefulShutdownService.stop();

        ArgumentCaptor<ShutdownMessage> captor = ArgumentCaptor.forClass(ShutdownMessage.class);
        verify(simpMessagingTemplate).convertAndSend(eq("/topic/system"), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("SYSTEM_SHUTDOWN");
    }

    @Test
    @DisplayName("stop() pauses all strategies")
    void stop_pausesAllStrategies() {
        when(accountRiskChecker.getDailyRealisedPnl()).thenReturn(BigDecimal.ZERO);
        when(killSwitchService.isActive()).thenReturn(false);

        gracefulShutdownService.stop();

        verify(strategyEngine).pauseAll();
    }

    @Test
    @DisplayName("stop() flushes data sync queues")
    void stop_flushesDataSync() {
        when(accountRiskChecker.getDailyRealisedPnl()).thenReturn(BigDecimal.ZERO);
        when(killSwitchService.isActive()).thenReturn(false);

        gracefulShutdownService.stop();

        verify(dataSyncService).flushAll();
    }

    @Test
    @DisplayName("stop() persists daily P&L to Redis with 18h TTL")
    void stop_persistsDailyPnl() {
        when(accountRiskChecker.getDailyRealisedPnl()).thenReturn(new BigDecimal("-3200.50"));
        when(killSwitchService.isActive()).thenReturn(false);

        gracefulShutdownService.stop();

        verify(valueOperations)
                .set(
                        org.mockito.ArgumentMatchers.startsWith("algo:daily:pnl:"),
                        eq("-3200.50"),
                        eq(Duration.ofHours(18)));
    }

    @Test
    @DisplayName("stop() persists kill switch state to Redis with 18h TTL")
    void stop_persistsKillSwitchState() {
        when(accountRiskChecker.getDailyRealisedPnl()).thenReturn(BigDecimal.ZERO);
        when(killSwitchService.isActive()).thenReturn(true);

        gracefulShutdownService.stop();

        verify(valueOperations).set(eq("algo:killswitch:active"), eq("true"), eq(Duration.ofHours(18)));
    }

    @Test
    @DisplayName("stop() disconnects Kite WebSocket")
    void stop_disconnectsKiteWebSocket() {
        when(accountRiskChecker.getDailyRealisedPnl()).thenReturn(BigDecimal.ZERO);
        when(killSwitchService.isActive()).thenReturn(false);

        gracefulShutdownService.stop();

        verify(kiteMarketDataService).disconnect();
    }

    @Test
    @DisplayName("stop() executes shutdown steps in correct order")
    void stop_executesInOrder() {
        when(accountRiskChecker.getDailyRealisedPnl()).thenReturn(BigDecimal.ZERO);
        when(killSwitchService.isActive()).thenReturn(false);

        gracefulShutdownService.start();
        gracefulShutdownService.stop();

        // Verify order: notify -> pause -> flush -> persist PnL -> persist killswitch -> disconnect
        InOrder order =
                inOrder(simpMessagingTemplate, strategyEngine, dataSyncService, valueOperations, kiteMarketDataService);
        order.verify(simpMessagingTemplate).convertAndSend(eq("/topic/system"), any(ShutdownMessage.class));
        order.verify(strategyEngine).pauseAll();
        order.verify(dataSyncService).flushAll();
        order.verify(valueOperations)
                .set(org.mockito.ArgumentMatchers.startsWith("algo:daily:pnl:"), anyString(), eq(Duration.ofHours(18)));
        order.verify(valueOperations).set(eq("algo:killswitch:active"), anyString(), eq(Duration.ofHours(18)));
        order.verify(kiteMarketDataService).disconnect();
    }

    @Test
    @DisplayName("SmartLifecycle phase is high (runs early in shutdown)")
    void getPhase_returnsHighValue() {
        assertThat(gracefulShutdownService.getPhase()).isEqualTo(Integer.MAX_VALUE - 1);
    }

    @Test
    @DisplayName("isAutoStartup returns true")
    void isAutoStartup_returnsTrue() {
        assertThat(gracefulShutdownService.isAutoStartup()).isTrue();
    }

    @Test
    @DisplayName("stop() logs shutdown decision via DecisionLogger")
    void stop_logsDecision() {
        when(accountRiskChecker.getDailyRealisedPnl()).thenReturn(BigDecimal.ZERO);
        when(killSwitchService.isActive()).thenReturn(false);

        gracefulShutdownService.stop();

        verify(decisionLogger)
                .log(
                        eq(com.algotrader.domain.enums.DecisionSource.RECOVERY),
                        any(),
                        eq(com.algotrader.domain.enums.DecisionType.GRACEFUL_SHUTDOWN),
                        any(),
                        anyString(),
                        any(),
                        any());
    }
}
