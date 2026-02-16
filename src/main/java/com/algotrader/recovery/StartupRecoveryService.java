package com.algotrader.recovery;

import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.DecisionOutcome;
import com.algotrader.domain.enums.DecisionSeverity;
import com.algotrader.domain.enums.DecisionSource;
import com.algotrader.domain.enums.DecisionType;
import com.algotrader.domain.enums.JournalStatus;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.model.Position;
import com.algotrader.entity.ExecutionJournalEntity;
import com.algotrader.entity.StrategyEntity;
import com.algotrader.entity.StrategyLegEntity;
import com.algotrader.event.SystemEvent;
import com.algotrader.event.SystemEventType;
import com.algotrader.mapper.JsonHelper;
import com.algotrader.observability.DecisionLogger;
import com.algotrader.reconciliation.PositionReconciliationService;
import com.algotrader.repository.jpa.ExecutionJournalJpaRepository;
import com.algotrader.repository.jpa.StrategyJpaRepository;
import com.algotrader.repository.jpa.StrategyLegJpaRepository;
import com.algotrader.repository.redis.PositionRedisRepository;
import com.algotrader.risk.AccountRiskChecker;
import com.algotrader.risk.KillSwitchService;
import com.algotrader.strategy.StrategyFactory;
import com.algotrader.strategy.adoption.PositionAdoptionService;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.BaseStrategyConfig;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Runs the daily startup recovery sequence after the application is ready.
 *
 * <p>Called by {@link com.algotrader.broker.StartupAuthRunner} after token acquisition
 * and instrument loading complete, guaranteeing the Kite session is available for
 * position reconciliation. This service handles the remaining recovery steps:
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
    private final StrategyJpaRepository strategyJpaRepository;
    private final StrategyLegJpaRepository strategyLegJpaRepository;
    private final StrategyFactory strategyFactory;
    private final PositionAdoptionService positionAdoptionService;

    public StartupRecoveryService(
            ExecutionJournalJpaRepository executionJournalJpaRepository,
            PositionReconciliationService positionReconciliationService,
            PositionRedisRepository positionRedisRepository,
            StrategyEngine strategyEngine,
            AccountRiskChecker accountRiskChecker,
            KillSwitchService killSwitchService,
            RedisTemplate<String, String> redisTemplate,
            ApplicationEventPublisher applicationEventPublisher,
            DecisionLogger decisionLogger,
            StrategyJpaRepository strategyJpaRepository,
            StrategyLegJpaRepository strategyLegJpaRepository,
            StrategyFactory strategyFactory,
            PositionAdoptionService positionAdoptionService) {
        this.executionJournalJpaRepository = executionJournalJpaRepository;
        this.positionReconciliationService = positionReconciliationService;
        this.positionRedisRepository = positionRedisRepository;
        this.strategyEngine = strategyEngine;
        this.accountRiskChecker = accountRiskChecker;
        this.killSwitchService = killSwitchService;
        this.redisTemplate = redisTemplate;
        this.applicationEventPublisher = applicationEventPublisher;
        this.decisionLogger = decisionLogger;
        this.strategyJpaRepository = strategyJpaRepository;
        this.strategyLegJpaRepository = strategyLegJpaRepository;
        this.strategyFactory = strategyFactory;
        this.positionAdoptionService = positionAdoptionService;
    }

    /**
     * Runs the daily startup recovery sequence. Called by StartupAuthRunner after
     * auth + instrument loading completes, ensuring the Kite session is available
     * for position reconciliation.
     */
    public void runRecovery() {
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

    /**
     * Restores strategies from H2 that were active before shutdown.
     * All restored strategies are set to PAUSED for safety — the trader must
     * manually resume them after verifying market conditions.
     *
     * <p>After all strategies are restored, populates the position→strategy
     * reverse index via PositionAdoptionService (orphaned leg cleanup + index build).
     */
    void resumeStrategies(RecoveryResult recoveryResult) {
        if (recoveryResult.isKillSwitchWasActive()) {
            log.warn("Kill switch was active before shutdown, not resuming strategies");
            // Still populate the position index even if not resuming strategies
            positionAdoptionService.populatePositionIndexOnStartup();
            return;
        }

        List<StrategyEntity> restorableEntities = strategyJpaRepository.findRestorableStrategies();

        if (restorableEntities.isEmpty()) {
            log.info("No strategies to restore from H2");
            recoveryResult.setStrategiesResumed(0);
            positionAdoptionService.populatePositionIndexOnStartup();
            return;
        }

        log.info("Found {} strategies to restore from H2", restorableEntities.size());
        int resumedCount = 0;

        for (StrategyEntity entity : restorableEntities) {
            try {
                // Deserialize polymorphic config from JSON (uses @JsonTypeInfo on BaseStrategyConfig)
                BaseStrategyConfig config = JsonHelper.fromJson(entity.getConfig(), BaseStrategyConfig.class);

                // Recreate strategy instance with original ID
                BaseStrategy strategy =
                        strategyFactory.restore(entity.getId(), entity.getType(), entity.getName(), config);

                // Register in StrategyEngine (injects services, adds to activeStrategies map)
                strategyEngine.registerRestoredStrategy(strategy);

                // Load positions from Redis via strategy legs
                restoreStrategyPositions(strategy, entity.getId());

                // All restored strategies come back as PAUSED for safety
                strategy.pause();
                entity.setStatus(StrategyStatus.PAUSED);
                strategyJpaRepository.save(entity);

                resumedCount++;
                log.info(
                        "Restored strategy {}: name='{}', type={}, positions={}",
                        entity.getId(),
                        entity.getName(),
                        entity.getType(),
                        strategy.getPositions().size());

            } catch (Exception e) {
                // Don't fail entire recovery for one bad strategy
                log.error("Failed to restore strategy {}: {}", entity.getId(), e.getMessage(), e);
            }
        }

        recoveryResult.setStrategiesResumed(resumedCount);
        log.info(
                "Strategy restoration complete: {} of {} restored (all in PAUSED state)",
                resumedCount,
                restorableEntities.size());

        // Now that all strategies are in-memory, build the position→strategy reverse index
        // and clean up any orphaned legs from strategies that failed to restore
        positionAdoptionService.populatePositionIndexOnStartup();
    }

    /**
     * Loads positions from Redis for a restored strategy by reading its legs from H2.
     * Recalculates entry premium from current position average prices.
     */
    private void restoreStrategyPositions(BaseStrategy strategy, String strategyId) {
        List<StrategyLegEntity> legs = strategyLegJpaRepository.findByStrategyId(strategyId);

        for (StrategyLegEntity leg : legs) {
            if (leg.getPositionId() == null) {
                continue;
            }
            positionRedisRepository
                    .findById(leg.getPositionId())
                    .ifPresentOrElse(
                            strategy::addPosition,
                            () -> log.warn(
                                    "Leg {} references position {} but position not found in Redis",
                                    leg.getId(),
                                    leg.getPositionId()));
        }

        // Recalculate entry premium from restored positions
        if (!strategy.getPositions().isEmpty()) {
            BigDecimal totalPremium = strategy.getPositions().stream()
                    .filter(p -> p.getAveragePrice() != null)
                    .map(p -> p.getAveragePrice().multiply(BigDecimal.valueOf(Math.abs(p.getQuantity()))))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int totalQuantity = strategy.getPositions().stream()
                    .mapToInt(p -> Math.abs(p.getQuantity()))
                    .sum();
            if (totalQuantity > 0) {
                strategy.setEntryPremium(totalPremium.divide(BigDecimal.valueOf(totalQuantity), MathContext.DECIMAL64));
            }
        }
    }
}
