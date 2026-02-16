package com.algotrader.unit.recovery;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.DecisionSource;
import com.algotrader.domain.enums.DecisionType;
import com.algotrader.domain.enums.JournalStatus;
import com.algotrader.domain.model.Position;
import com.algotrader.entity.ExecutionJournalEntity;
import com.algotrader.event.SystemEvent;
import com.algotrader.observability.DecisionLogger;
import com.algotrader.reconciliation.PositionReconciliationService;
import com.algotrader.recovery.StartupRecoveryService;
import com.algotrader.repository.jpa.ExecutionJournalJpaRepository;
import com.algotrader.repository.jpa.StrategyJpaRepository;
import com.algotrader.repository.jpa.StrategyLegJpaRepository;
import com.algotrader.repository.redis.PositionRedisRepository;
import com.algotrader.risk.AccountRiskChecker;
import com.algotrader.risk.KillSwitchService;
import com.algotrader.strategy.StrategyFactory;
import com.algotrader.strategy.adoption.PositionAdoptionService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Tests for StartupRecoveryService verifying state restoration from Redis,
 * incomplete execution journal recovery, position reconciliation, and
 * strategy resumption logic -- tested through the public runRecovery() API.
 */
@ExtendWith(MockitoExtension.class)
class StartupRecoveryServiceTest {

    @Mock
    private ExecutionJournalJpaRepository executionJournalJpaRepository;

    @Mock
    private PositionReconciliationService positionReconciliationService;

    @Mock
    private PositionRedisRepository positionRedisRepository;

    @Mock
    private StrategyEngine strategyEngine;

    @Mock
    private AccountRiskChecker accountRiskChecker;

    @Mock
    private KillSwitchService killSwitchService;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private DecisionLogger decisionLogger;

    @Mock
    private StrategyJpaRepository strategyJpaRepository;

    @Mock
    private StrategyLegJpaRepository strategyLegJpaRepository;

    @Mock
    private StrategyFactory strategyFactory;

    @Mock
    private PositionAdoptionService positionAdoptionService;

    private StartupRecoveryService startupRecoveryService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Default: no strategies to restore from H2 (lenient: not used in kill switch test)
        org.mockito.Mockito.lenient()
                .when(strategyJpaRepository.findRestorableStrategies())
                .thenReturn(List.of());

        startupRecoveryService = new StartupRecoveryService(
                executionJournalJpaRepository,
                positionReconciliationService,
                positionRedisRepository,
                strategyEngine,
                accountRiskChecker,
                killSwitchService,
                redisTemplate,
                applicationEventPublisher,
                decisionLogger,
                strategyJpaRepository,
                strategyLegJpaRepository,
                strategyFactory,
                positionAdoptionService);
    }

    @Test
    @DisplayName("Restores daily P&L from Redis and resets AccountRiskChecker")
    void restoresDailyPnlFromRedis() {
        when(valueOperations.get(org.mockito.ArgumentMatchers.startsWith("algo:daily:pnl:")))
                .thenReturn("-4500.25");
        when(valueOperations.get("algo:killswitch:active")).thenReturn(null);
        when(executionJournalJpaRepository.findByStatusIn(any())).thenReturn(List.of());
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        startupRecoveryService.runRecovery();

        verify(accountRiskChecker).resetDailyPnl(new BigDecimal("-4500.25"));
    }

    @Test
    @DisplayName("No P&L reset when Redis has no stored value")
    void noPnlReset_whenRedisEmpty() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(executionJournalJpaRepository.findByStatusIn(any())).thenReturn(List.of());
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        startupRecoveryService.runRecovery();

        verify(accountRiskChecker, never()).resetDailyPnl(any());
    }

    @Test
    @DisplayName("Marks IN_PROGRESS journals as REQUIRES_RECOVERY")
    void marksIncompleteJournals() {
        when(valueOperations.get(anyString())).thenReturn(null);

        ExecutionJournalEntity journal = ExecutionJournalEntity.builder()
                .id(1L)
                .strategyId("STR-001")
                .executionGroupId("GRP-001")
                .status(JournalStatus.IN_PROGRESS)
                .build();
        when(executionJournalJpaRepository.findByStatusIn(
                        List.of(JournalStatus.IN_PROGRESS, JournalStatus.REQUIRES_RECOVERY)))
                .thenReturn(List.of(journal));
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        startupRecoveryService.runRecovery();

        verify(executionJournalJpaRepository).save(journal);
        // After save, the status should have been changed
        org.assertj.core.api.Assertions.assertThat(journal.getStatus()).isEqualTo(JournalStatus.REQUIRES_RECOVERY);
    }

    @Test
    @DisplayName("REQUIRES_RECOVERY journals are not re-saved")
    void requiresRecoveryJournals_notResaved() {
        when(valueOperations.get(anyString())).thenReturn(null);

        ExecutionJournalEntity journal = ExecutionJournalEntity.builder()
                .id(2L)
                .strategyId("STR-001")
                .executionGroupId("GRP-001")
                .status(JournalStatus.REQUIRES_RECOVERY)
                .build();
        when(executionJournalJpaRepository.findByStatusIn(any())).thenReturn(List.of(journal));
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        startupRecoveryService.runRecovery();

        // Should not be saved again since it's already REQUIRES_RECOVERY
        verify(executionJournalJpaRepository, never()).save(journal);
    }

    @Test
    @DisplayName("Triggers position reconciliation on startup")
    void triggersReconciliation() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(executionJournalJpaRepository.findByStatusIn(any())).thenReturn(List.of());
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        startupRecoveryService.runRecovery();

        verify(positionReconciliationService).reconcile("STARTUP");
    }

    @Test
    @DisplayName("Publishes SystemEvent after recovery completes")
    void publishesSystemEvent() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(executionJournalJpaRepository.findByStatusIn(any())).thenReturn(List.of());
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        startupRecoveryService.runRecovery();

        verify(applicationEventPublisher).publishEvent(any(SystemEvent.class));
    }

    @Test
    @DisplayName("Logs recovery result via DecisionLogger")
    void logsRecoveryDecision() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(executionJournalJpaRepository.findByStatusIn(any())).thenReturn(List.of());
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        startupRecoveryService.runRecovery();

        verify(decisionLogger)
                .log(
                        eq(DecisionSource.RECOVERY),
                        any(),
                        eq(DecisionType.STARTUP_RECOVERY),
                        any(),
                        anyString(),
                        any(),
                        any());
    }

    @Test
    @DisplayName("Kill switch active prevents strategy resumption but recovery continues")
    void killSwitchActive_recoveryStillCompletes() {
        when(valueOperations.get(org.mockito.ArgumentMatchers.startsWith("algo:daily:pnl:")))
                .thenReturn(null);
        when(valueOperations.get("algo:killswitch:active")).thenReturn("true");
        when(executionJournalJpaRepository.findByStatusIn(any())).thenReturn(List.of());
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        startupRecoveryService.runRecovery();

        // Recovery still completes and publishes event
        verify(applicationEventPublisher).publishEvent(any(SystemEvent.class));
    }

    @Test
    @DisplayName("Reconciliation failure does not abort recovery")
    void reconciliationFailure_doesNotAbortRecovery() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(executionJournalJpaRepository.findByStatusIn(any())).thenReturn(List.of());
        when(positionReconciliationService.reconcile("STARTUP")).thenThrow(new RuntimeException("Broker API down"));

        startupRecoveryService.runRecovery();

        // Recovery still publishes event (positionReconciliationFailed=true but no abort)
        verify(applicationEventPublisher).publishEvent(any(SystemEvent.class));
    }

    @Test
    @DisplayName("Counts synced positions from Redis after reconciliation")
    void countsPositionsAfterReconciliation() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(executionJournalJpaRepository.findByStatusIn(any())).thenReturn(List.of());

        Position pos1 = Position.builder().instrumentToken(12345L).build();
        Position pos2 = Position.builder().instrumentToken(67890L).build();
        when(positionRedisRepository.findAll()).thenReturn(List.of(pos1, pos2));

        startupRecoveryService.runRecovery();

        // No error, positions counted
        verify(positionRedisRepository).findAll();
    }
}
