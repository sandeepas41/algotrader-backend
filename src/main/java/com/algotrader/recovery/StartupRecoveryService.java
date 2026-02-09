package com.algotrader.recovery;

import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.DecisionOutcome;
import com.algotrader.domain.enums.DecisionSeverity;
import com.algotrader.domain.enums.DecisionSource;
import com.algotrader.domain.enums.DecisionType;
import com.algotrader.domain.enums.JournalStatus;
import com.algotrader.domain.model.Position;
import com.algotrader.entity.ExecutionJournalEntity;
import com.algotrader.event.SystemEvent;
import com.algotrader.event.SystemEventType;
import com.algotrader.observability.DecisionLogger;
import com.algotrader.reconciliation.PositionReconciliationService;
import com.algotrader.repository.jpa.ExecutionJournalJpaRepository;
import com.algotrader.repository.redis.PositionRedisRepository;
import com.algotrader.risk.AccountRiskChecker;
import com.algotrader.risk.KillSwitchService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Runs the daily startup recovery sequence after the application is ready.
 *
 * <p>Executes AFTER {@link com.algotrader.broker.StartupAuthRunner} completes token
 * acquisition and instrument loading. This service handles the remaining recovery steps:
 * <ol>
 *   <li>Restore Redis state (daily P&L, kill switch)</li>
 *   <li>Scan ExecutionJournal for incomplete multi-leg operations</li>
 *   <li>Reconcile positions with broker</li>
 *   <li>Resume strategies that were ACTIVE before shutdown</li>
 * </ol>
 *
 * <p>On completion, publishes a {@link SystemEvent} with type APPLICATION_READY
 * so downstream components know the system is fully operational. Each step is
 * logged as a DecisionLog entry for audit trail.
 */
@Service
public class StartupRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(StartupRecoveryService.class);

    private final ExecutionJournalJpaRepository executionJournalJpaRepository;
    private final PositionReconciliationService positionReconciliationService;
    private final PositionRedisRepository positionRedisRepository;
    private final StrategyEngine strategyEngine;
    private final AccountRiskChecker accountRiskChecker;
    private final KillSwitchService killSwitchService;
    private final RedisTemplate<String, String> redisTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final DecisionLogger decisionLogger;

    public StartupRecoveryService(
            ExecutionJournalJpaRepository executionJournalJpaRepository,
            PositionReconciliationService positionReconciliationService,
            PositionRedisRepository positionRedisRepository,
            StrategyEngine strategyEngine,
            AccountRiskChecker accountRiskChecker,
            KillSwitchService killSwitchService,
            RedisTemplate<String, String> redisTemplate,
            ApplicationEventPublisher applicationEventPublisher,
            DecisionLogger decisionLogger) {
        this.executionJournalJpaRepository = executionJournalJpaRepository;
        this.positionReconciliationService = positionReconciliationService;
        this.positionRedisRepository = positionRedisRepository;
        this.strategyEngine = strategyEngine;
        this.accountRiskChecker = accountRiskChecker;
        this.killSwitchService = killSwitchService;
        this.redisTemplate = redisTemplate;
        this.applicationEventPublisher = applicationEventPublisher;
        this.decisionLogger = decisionLogger;
    }

    /**
     * Runs after ApplicationReadyEvent with Order(10) to give StartupAuthRunner (Order default)
     * time to complete token acquisition and instrument loading.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void onApplicationReady() {
        log.info("Starting daily recovery sequence...");

        RecoveryResult recoveryResult =
                RecoveryResult.builder().startedAt(System.currentTimeMillis()).build();

        try {
            // Step 3: Restore Redis state (daily P&L, kill switch)
            restoreState(recoveryResult);

            // Step 4: Scan ExecutionJournal for incomplete multi-leg operations
            recoverIncompleteExecutions(recoveryResult);

            // Step 5: Reconcile positions with broker
            reconcilePositions(recoveryResult);

            // Step 6: Resume strategies that were ACTIVE before shutdown
            resumeStrategies(recoveryResult);

            recoveryResult.setSuccess(true);
            log.info("Recovery sequence completed successfully");

        } catch (Exception e) {
            recoveryResult.setSuccess(false);
            recoveryResult.setError(e.getMessage());
            log.error("Recovery sequence failed", e);
        }

        recoveryResult.setDurationMs(System.currentTimeMillis() - recoveryResult.getStartedAt());

        // Log recovery summary
        decisionLogger.log(
                DecisionSource.RECOVERY,
                null,
                DecisionType.STARTUP_RECOVERY,
                recoveryResult.isSuccess() ? DecisionOutcome.INFO : DecisionOutcome.FAILED,
                String.format(
                        "Startup recovery %s: duration=%dms, pnlRestored=%s, incompleteJournals=%d, positionsSynced=%d, strategiesResumed=%d",
                        recoveryResult.isSuccess() ? "completed" : "failed",
                        recoveryResult.getDurationMs(),
                        recoveryResult.getRestoredDailyPnL(),
                        recoveryResult.getIncompleteExecutionsFound(),
                        recoveryResult.getPositionsSynced(),
                        recoveryResult.getStrategiesResumed()),
                Map.of(
                        "success",
                        recoveryResult.isSuccess(),
                        "durationMs",
                        recoveryResult.getDurationMs(),
                        "incompleteExecutions",
                        recoveryResult.getIncompleteExecutionsFound(),
                        "positionsSynced",
                        recoveryResult.getPositionsSynced(),
                        "strategiesResumed",
                        recoveryResult.getStrategiesResumed()),
                DecisionSeverity.INFO);

        // Publish system ready event
        applicationEventPublisher.publishEvent(new SystemEvent(
                this,
                SystemEventType.APPLICATION_READY,
                "Startup recovery " + (recoveryResult.isSuccess() ? "completed" : "failed"),
                Map.of("recoveryDurationMs", recoveryResult.getDurationMs())));
    }

    void restoreState(RecoveryResult recoveryResult) {
        // Restore daily realized P&L from Redis
        String dailyPnlKey = "algo:daily:pnl:" + LocalDate.now();
        String storedPnl = redisTemplate.opsForValue().get(dailyPnlKey);
        if (storedPnl != null) {
            BigDecimal pnl = new BigDecimal(storedPnl);
            accountRiskChecker.resetDailyPnl(pnl);
            recoveryResult.setRestoredDailyPnL(pnl);
            log.info("Restored daily P&L from Redis: {}", pnl);
        }

        // Check kill switch state
        String killSwitchKey = "algo:killswitch:active";
        String killSwitchState = redisTemplate.opsForValue().get(killSwitchKey);
        if ("true".equals(killSwitchState)) {
            recoveryResult.setKillSwitchWasActive(true);
            log.warn("Kill switch was active before shutdown");
        }
    }

    void recoverIncompleteExecutions(RecoveryResult recoveryResult) {
        List<ExecutionJournalEntity> incompleteJournals = executionJournalJpaRepository.findByStatusIn(
                List.of(JournalStatus.IN_PROGRESS, JournalStatus.REQUIRES_RECOVERY));

        recoveryResult.setIncompleteExecutionsFound(incompleteJournals.size());

        if (incompleteJournals.isEmpty()) {
            log.info("No incomplete execution journals found");
            return;
        }

        log.warn("Found {} incomplete execution journal entries", incompleteJournals.size());

        // Group by executionGroupId and mark REQUIRES_RECOVERY for manual review
        for (ExecutionJournalEntity journal : incompleteJournals) {
            if (journal.getStatus() == JournalStatus.IN_PROGRESS) {
                journal.setStatus(JournalStatus.REQUIRES_RECOVERY);
                executionJournalJpaRepository.save(journal);
                log.warn(
                        "Marked execution journal {} (group={}, strategy={}) as REQUIRES_RECOVERY",
                        journal.getId(),
                        journal.getExecutionGroupId(),
                        journal.getStrategyId());
            }
            recoveryResult.getRecoveredExecutionGroups().add(journal.getExecutionGroupId());
        }
    }

    void reconcilePositions(RecoveryResult recoveryResult) {
        try {
            positionReconciliationService.reconcile("STARTUP");
            List<Position> syncedPositions = positionRedisRepository.findAll();
            recoveryResult.setPositionsSynced(syncedPositions.size());
            log.info("Position reconciliation complete: {} positions in Redis", syncedPositions.size());
        } catch (Exception e) {
            recoveryResult.setPositionReconciliationFailed(true);
            log.error("Position reconciliation failed during startup", e);
        }
    }

    void resumeStrategies(RecoveryResult recoveryResult) {
        if (recoveryResult.isKillSwitchWasActive()) {
            log.warn("Kill switch was active before shutdown, not resuming strategies");
            return;
        }

        // #TODO: Resume strategies that were ACTIVE before shutdown.
        // Currently StrategyEngine.pauseAll() pauses in-memory strategies,
        // but there is no persistent "was-active" flag yet.
        // When strategy state persistence is added, this will query the DB
        // for strategies with status ACTIVE and resume them.
        recoveryResult.setStrategiesResumed(0);
        log.info("Strategy resumption: no persistent strategy state available yet");
    }
}
