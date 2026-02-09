package com.algotrader.recovery;

import com.algotrader.broker.KiteMarketDataService;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.DecisionOutcome;
import com.algotrader.domain.enums.DecisionSeverity;
import com.algotrader.domain.enums.DecisionSource;
import com.algotrader.domain.enums.DecisionType;
import com.algotrader.observability.DecisionLogger;
import com.algotrader.risk.AccountRiskChecker;
import com.algotrader.risk.KillSwitchService;
import com.algotrader.service.DataSyncService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Ensures orderly shutdown: notify frontend, pause strategies, flush data, persist state.
 *
 * <p>Implements {@link SmartLifecycle} with a high phase value so it runs BEFORE other
 * Spring components shut down. The shutdown sequence:
 * <ol>
 *   <li>Notify frontend via WebSocket (500ms pause for delivery)</li>
 *   <li>Pause all strategies (positions remain open)</li>
 *   <li>Flush DataSyncService queues to H2</li>
 *   <li>Persist daily P&L to Redis (18h TTL for next-day recovery)</li>
 *   <li>Persist kill switch state to Redis</li>
 *   <li>Disconnect Kite WebSocket</li>
 * </ol>
 *
 * <p>Strategies are paused, NOT closed. This avoids placing exit orders during an
 * uncertain shutdown. On next startup, StartupRecoveryService resumes them.
 */
@Service
public class GracefulShutdownService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownService.class);

    private final DataSyncService dataSyncService;
    private final AccountRiskChecker accountRiskChecker;
    private final StrategyEngine strategyEngine;
    private final KillSwitchService killSwitchService;
    private final KiteMarketDataService kiteMarketDataService;
    private final RedisTemplate<String, String> redisTemplate;
    private final SimpMessagingTemplate simpMessagingTemplate;
    private final DecisionLogger decisionLogger;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public GracefulShutdownService(
            DataSyncService dataSyncService,
            AccountRiskChecker accountRiskChecker,
            StrategyEngine strategyEngine,
            KillSwitchService killSwitchService,
            KiteMarketDataService kiteMarketDataService,
            RedisTemplate<String, String> redisTemplate,
            SimpMessagingTemplate simpMessagingTemplate,
            DecisionLogger decisionLogger) {
        this.dataSyncService = dataSyncService;
        this.accountRiskChecker = accountRiskChecker;
        this.strategyEngine = strategyEngine;
        this.killSwitchService = killSwitchService;
        this.kiteMarketDataService = kiteMarketDataService;
        this.redisTemplate = redisTemplate;
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.decisionLogger = decisionLogger;
    }

    @Override
    public void start() {
        running.set(true);
        log.info("GracefulShutdownService started");
    }

    @Override
    public void stop() {
        log.info("Graceful shutdown initiated...");

        try {
            // Step 1: Notify frontend
            notifyFrontend();

            // Step 2: Pause all strategies (do NOT close positions)
            pauseStrategies();

            // Step 3: Flush data sync queues
            flushDataSync();

            // Step 4: Persist daily P&L to Redis
            persistDailyPnL();

            // Step 5: Persist kill switch state
            persistKillSwitchState();

            // Step 6: Disconnect Kite WebSocket
            disconnectWebSocket();

            // Log shutdown completion
            decisionLogger.log(
                    DecisionSource.RECOVERY,
                    null,
                    DecisionType.GRACEFUL_SHUTDOWN,
                    DecisionOutcome.INFO,
                    "Graceful shutdown completed successfully",
                    Map.of(),
                    DecisionSeverity.INFO);

            log.info("Graceful shutdown completed successfully");

        } catch (Exception e) {
            log.error("Error during graceful shutdown", e);
        } finally {
            running.set(false);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // Run before other Spring components shut down (higher phase = earlier shutdown)
        return Integer.MAX_VALUE - 1;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    void notifyFrontend() {
        try {
            ShutdownMessage shutdownMessage = ShutdownMessage.builder()
                    .type("SYSTEM_SHUTDOWN")
                    .message("Server is shutting down for maintenance")
                    .timestamp(System.currentTimeMillis())
                    .build();
            simpMessagingTemplate.convertAndSend("/topic/system", shutdownMessage);
            log.info("Frontend notified of shutdown");

            // Brief pause to allow WebSocket message delivery
            Thread.sleep(500);
        } catch (Exception e) {
            log.warn("Failed to notify frontend of shutdown", e);
        }
    }

    void pauseStrategies() {
        strategyEngine.pauseAll();
        log.info("All strategies paused for shutdown");
    }

    void flushDataSync() {
        try {
            dataSyncService.flushAll();
            log.info("Data sync queues flushed");
        } catch (Exception e) {
            log.warn("Failed to flush data sync queues", e);
        }
    }

    void persistDailyPnL() {
        BigDecimal dailyPnl = accountRiskChecker.getDailyRealisedPnl();
        String key = "algo:daily:pnl:" + LocalDate.now();
        redisTemplate.opsForValue().set(key, dailyPnl.toPlainString(), Duration.ofHours(18));
        log.info("Persisted daily P&L to Redis: {}", dailyPnl);
    }

    void persistKillSwitchState() {
        String key = "algo:killswitch:active";
        redisTemplate.opsForValue().set(key, String.valueOf(killSwitchService.isActive()), Duration.ofHours(18));
        log.info("Persisted kill switch state: {}", killSwitchService.isActive());
    }

    void disconnectWebSocket() {
        try {
            kiteMarketDataService.disconnect();
            log.info("Kite WebSocket disconnected");
        } catch (Exception e) {
            log.warn("Failed to disconnect Kite WebSocket cleanly", e);
        }
    }
}
