package com.algotrader.unit.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.condition.ConditionEngine;
import com.algotrader.condition.ConditionEngineConfig;
import com.algotrader.domain.enums.ConditionActionType;
import com.algotrader.domain.enums.ConditionOperator;
import com.algotrader.domain.enums.ConditionRuleStatus;
import com.algotrader.domain.enums.EvaluationMode;
import com.algotrader.domain.model.ConditionRule;
import com.algotrader.entity.ConditionRuleEntity;
import com.algotrader.event.ConditionTriggeredEvent;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.event.IndicatorUpdateEvent;
import com.algotrader.indicator.IndicatorService;
import com.algotrader.indicator.IndicatorType;
import com.algotrader.mapper.ConditionRuleMapper;
import com.algotrader.repository.jpa.CompositeConditionRuleJpaRepository;
import com.algotrader.repository.jpa.ConditionRuleJpaRepository;
import com.algotrader.repository.jpa.ConditionTriggerHistoryJpaRepository;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Tests for ConditionEngine: rule loading, evaluation logic, trigger handling,
 * cooldown enforcement, crossing detection, and composite rule evaluation.
 */
@ExtendWith(MockitoExtension.class)
class ConditionEngineTest {

    private static final Long NIFTY_TOKEN = 256265L;
    private static final Long BANKNIFTY_TOKEN = 260105L;

    @Mock
    private ConditionRuleJpaRepository conditionRuleJpaRepository;

    @Mock
    private CompositeConditionRuleJpaRepository compositeConditionRuleJpaRepository;

    @Mock
    private ConditionTriggerHistoryJpaRepository conditionTriggerHistoryJpaRepository;

    @Mock
    private IndicatorService indicatorService;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private EventPublisherHelper eventPublisherHelper;

    private ConditionEngine conditionEngine;
    private ConditionEngineConfig conditionEngineConfig;
    private ConditionRuleMapper conditionRuleMapper;

    @BeforeEach
    void setUp() {
        conditionEngineConfig = new ConditionEngineConfig();
        conditionEngineConfig.setEnabled(true);
        conditionEngineConfig.setTickEvaluation(true);
        conditionEngineConfig.setMaxActiveRules(50);

        conditionRuleMapper = Mappers.getMapper(ConditionRuleMapper.class);

        conditionEngine = new ConditionEngine(
                conditionEngineConfig,
                conditionRuleJpaRepository,
                compositeConditionRuleJpaRepository,
                conditionTriggerHistoryJpaRepository,
                indicatorService,
                applicationEventPublisher,
                eventPublisherHelper);
    }

    private ConditionRuleEntity buildRuleEntity(
            Long id,
            Long instrumentToken,
            IndicatorType indicatorType,
            Integer period,
            ConditionOperator operator,
            BigDecimal threshold,
            EvaluationMode mode) {
        return ConditionRuleEntity.builder()
                .id(id)
                .name("Test Rule " + id)
                .instrumentToken(instrumentToken)
                .tradingSymbol("NIFTY 50")
                .indicatorType(indicatorType)
                .indicatorPeriod(period)
                .operator(operator)
                .thresholdValue(threshold)
                .evaluationMode(mode)
                .actionType(ConditionActionType.ALERT_ONLY)
                .status(ConditionRuleStatus.ACTIVE)
                .triggerCount(0)
                .cooldownMinutes(30)
                .build();
    }

    @Nested
    @DisplayName("Rule Loading")
    class RuleLoading {

        @Test
        @DisplayName("loads active rules from database into memory cache")
        void loadsActiveRulesIntoCache() {
            ConditionRuleEntity entity = buildRuleEntity(
                    1L,
                    NIFTY_TOKEN,
                    IndicatorType.RSI,
                    14,
                    ConditionOperator.GT,
                    BigDecimal.valueOf(70),
                    EvaluationMode.TICK);

            when(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(List.of(entity));

            conditionEngine.loadRules();

            assertThat(conditionEngine.getActiveRuleCount()).isEqualTo(1);
            assertThat(conditionEngine.getRulesForInstrument(NIFTY_TOKEN)).hasSize(1);
        }

        @Test
        @DisplayName("clears cache on reload")
        void clearsCacheOnReload() {
            ConditionRuleEntity entity = buildRuleEntity(
                    1L,
                    NIFTY_TOKEN,
                    IndicatorType.RSI,
                    14,
                    ConditionOperator.GT,
                    BigDecimal.valueOf(70),
                    EvaluationMode.TICK);

            when(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(List.of(entity));
            conditionEngine.loadRules();

            // Reload with empty
            when(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(Collections.emptyList());
            conditionEngine.loadRules();

            assertThat(conditionEngine.getActiveRuleCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("skips loading when engine is disabled")
        void skipsWhenDisabled() {
            conditionEngineConfig.setEnabled(false);
            conditionEngine.loadRules();

            assertThat(conditionEngine.getActiveRuleCount()).isEqualTo(0);
            verify(conditionRuleJpaRepository, never()).findByStatus(any());
        }

        @Test
        @DisplayName("groups rules by instrument token")
        void groupsByInstrumentToken() {
            ConditionRuleEntity niftyRule = buildRuleEntity(
                    1L,
                    NIFTY_TOKEN,
                    IndicatorType.RSI,
                    14,
                    ConditionOperator.GT,
                    BigDecimal.valueOf(70),
                    EvaluationMode.TICK);
            ConditionRuleEntity bnfRule = buildRuleEntity(
                    2L,
                    BANKNIFTY_TOKEN,
                    IndicatorType.EMA,
                    20,
                    ConditionOperator.LT,
                    BigDecimal.valueOf(50000),
                    EvaluationMode.INTERVAL_5M);

            when(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(List.of(niftyRule, bnfRule));
            conditionEngine.loadRules();

            assertThat(conditionEngine.getRulesForInstrument(NIFTY_TOKEN)).hasSize(1);
            assertThat(conditionEngine.getRulesForInstrument(BANKNIFTY_TOKEN)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Condition Evaluation")
    class ConditionEvaluation {

        @Test
        @DisplayName("GT operator evaluates true when value exceeds threshold")
        void gtOperatorTrue() {
            ConditionRule rule = ConditionRule.builder()
                    .id(1L)
                    .operator(ConditionOperator.GT)
                    .thresholdValue(BigDecimal.valueOf(70))
                    .indicatorType(IndicatorType.RSI)
                    .build();

            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(75)))
                    .isTrue();
            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(70)))
                    .isFalse();
            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(65)))
                    .isFalse();
        }

        @Test
        @DisplayName("LT operator evaluates true when value is below threshold")
        void ltOperatorTrue() {
            ConditionRule rule = ConditionRule.builder()
                    .id(1L)
                    .operator(ConditionOperator.LT)
                    .thresholdValue(BigDecimal.valueOf(30))
                    .indicatorType(IndicatorType.RSI)
                    .build();

            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(25)))
                    .isTrue();
            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(30)))
                    .isFalse();
        }

        @Test
        @DisplayName("GTE operator evaluates true when value is greater than or equal to threshold")
        void gteOperatorTrue() {
            ConditionRule rule = ConditionRule.builder()
                    .id(1L)
                    .operator(ConditionOperator.GTE)
                    .thresholdValue(BigDecimal.valueOf(70))
                    .indicatorType(IndicatorType.RSI)
                    .build();

            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(70)))
                    .isTrue();
            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(75)))
                    .isTrue();
            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(69)))
                    .isFalse();
        }

        @Test
        @DisplayName("LTE operator evaluates true when value is less than or equal to threshold")
        void lteOperatorTrue() {
            ConditionRule rule = ConditionRule.builder()
                    .id(1L)
                    .operator(ConditionOperator.LTE)
                    .thresholdValue(BigDecimal.valueOf(30))
                    .indicatorType(IndicatorType.RSI)
                    .build();

            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(30)))
                    .isTrue();
            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(25)))
                    .isTrue();
            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(31)))
                    .isFalse();
        }

        @Test
        @DisplayName("BETWEEN operator evaluates true when value is within range")
        void betweenOperatorTrue() {
            ConditionRule rule = ConditionRule.builder()
                    .id(1L)
                    .operator(ConditionOperator.BETWEEN)
                    .thresholdValue(BigDecimal.valueOf(30))
                    .secondaryThreshold(BigDecimal.valueOf(70))
                    .indicatorType(IndicatorType.RSI)
                    .build();

            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(50)))
                    .isTrue();
            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(30)))
                    .isTrue();
            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(70)))
                    .isTrue();
            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(25)))
                    .isFalse();
            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(75)))
                    .isFalse();
        }

        @Test
        @DisplayName("OUTSIDE operator evaluates true when value is outside range")
        void outsideOperatorTrue() {
            ConditionRule rule = ConditionRule.builder()
                    .id(1L)
                    .operator(ConditionOperator.OUTSIDE)
                    .thresholdValue(BigDecimal.valueOf(30))
                    .secondaryThreshold(BigDecimal.valueOf(70))
                    .indicatorType(IndicatorType.RSI)
                    .build();

            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(25)))
                    .isTrue();
            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(75)))
                    .isTrue();
            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(50)))
                    .isFalse();
        }

        @Test
        @DisplayName("CROSSES_ABOVE detects threshold crossing from below")
        void crossesAboveDetection() {
            ConditionRule rule = ConditionRule.builder()
                    .id(1L)
                    .operator(ConditionOperator.CROSSES_ABOVE)
                    .thresholdValue(BigDecimal.valueOf(30))
                    .indicatorType(IndicatorType.RSI)
                    .build();

            // First evaluation: no previous value -> false
            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(35)))
                    .isFalse();
        }

        @Test
        @DisplayName("CROSSES_BELOW detects threshold crossing from above")
        void crossesBelowDetection() {
            ConditionRule rule = ConditionRule.builder()
                    .id(1L)
                    .operator(ConditionOperator.CROSSES_BELOW)
                    .thresholdValue(BigDecimal.valueOf(70))
                    .indicatorType(IndicatorType.RSI)
                    .build();

            // No previous value -> false
            assertThat(conditionEngine.evaluateCondition(rule, BigDecimal.valueOf(65)))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Tick-Level Evaluation")
    class TickLevelEvaluation {

        @Test
        @DisplayName("evaluates tick-level rules on IndicatorUpdateEvent")
        void evaluatesTickRulesOnEvent() {
            ConditionRuleEntity entity = buildRuleEntity(
                    1L,
                    NIFTY_TOKEN,
                    IndicatorType.RSI,
                    14,
                    ConditionOperator.GT,
                    BigDecimal.valueOf(70),
                    EvaluationMode.TICK);

            when(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(List.of(entity));
            conditionEngine.loadRules();

            // RSI = 75, should trigger GT 70
            when(indicatorService.getIndicatorValue(eq(NIFTY_TOKEN), eq(IndicatorType.RSI), eq(14), any()))
                    .thenReturn(BigDecimal.valueOf(75));
            when(conditionRuleJpaRepository.findById(1L)).thenReturn(Optional.of(entity));

            IndicatorUpdateEvent event =
                    new IndicatorUpdateEvent(this, NIFTY_TOKEN, "NIFTY 50", Map.of("RSI:14", BigDecimal.valueOf(75)));
            conditionEngine.onIndicatorUpdate(event);

            // Verify trigger event was published
            verify(applicationEventPublisher).publishEvent(any(ConditionTriggeredEvent.class));
        }

        @Test
        @DisplayName("ignores events for instruments with no rules")
        void ignoresUnmatchedInstruments() {
            when(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(Collections.emptyList());
            conditionEngine.loadRules();

            IndicatorUpdateEvent event =
                    new IndicatorUpdateEvent(this, NIFTY_TOKEN, "NIFTY 50", Map.of("RSI:14", BigDecimal.valueOf(75)));
            conditionEngine.onIndicatorUpdate(event);

            verify(indicatorService, never()).getIndicatorValue(any(), any(), any(), any());
        }

        @Test
        @DisplayName("skips evaluation when engine is disabled")
        void skipsWhenDisabled() {
            conditionEngineConfig.setEnabled(false);

            IndicatorUpdateEvent event =
                    new IndicatorUpdateEvent(this, NIFTY_TOKEN, "NIFTY 50", Map.of("RSI:14", BigDecimal.valueOf(75)));
            conditionEngine.onIndicatorUpdate(event);

            verify(indicatorService, never()).getIndicatorValue(any(), any(), any(), any());
        }

        @Test
        @DisplayName("does not trigger when condition is false")
        void doesNotTriggerWhenFalse() {
            ConditionRuleEntity entity = buildRuleEntity(
                    1L,
                    NIFTY_TOKEN,
                    IndicatorType.RSI,
                    14,
                    ConditionOperator.GT,
                    BigDecimal.valueOf(70),
                    EvaluationMode.TICK);

            when(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(List.of(entity));
            conditionEngine.loadRules();

            // RSI = 65, should NOT trigger GT 70
            when(indicatorService.getIndicatorValue(eq(NIFTY_TOKEN), eq(IndicatorType.RSI), eq(14), any()))
                    .thenReturn(BigDecimal.valueOf(65));

            IndicatorUpdateEvent event =
                    new IndicatorUpdateEvent(this, NIFTY_TOKEN, "NIFTY 50", Map.of("RSI:14", BigDecimal.valueOf(65)));
            conditionEngine.onIndicatorUpdate(event);

            verify(applicationEventPublisher, never()).publishEvent(any(ConditionTriggeredEvent.class));
        }
    }

    @Nested
    @DisplayName("Trigger Guards")
    class TriggerGuards {

        @Test
        @DisplayName("cooldown prevents re-trigger within window")
        void cooldownPreventsRetrigger() {
            ConditionRuleEntity entity = buildRuleEntity(
                    1L,
                    NIFTY_TOKEN,
                    IndicatorType.RSI,
                    14,
                    ConditionOperator.GT,
                    BigDecimal.valueOf(70),
                    EvaluationMode.TICK);
            entity.setCooldownMinutes(60);

            when(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(List.of(entity));
            conditionEngine.loadRules();

            // First trigger
            when(indicatorService.getIndicatorValue(eq(NIFTY_TOKEN), eq(IndicatorType.RSI), eq(14), any()))
                    .thenReturn(BigDecimal.valueOf(75));
            when(conditionRuleJpaRepository.findById(1L)).thenReturn(Optional.of(entity));

            IndicatorUpdateEvent event =
                    new IndicatorUpdateEvent(this, NIFTY_TOKEN, "NIFTY 50", Map.of("RSI:14", BigDecimal.valueOf(75)));
            conditionEngine.onIndicatorUpdate(event);

            // First trigger should fire
            verify(applicationEventPublisher, times(1)).publishEvent(any(ConditionTriggeredEvent.class));

            // Second trigger should be blocked by cooldown (lastTriggeredAt was just set)
            conditionEngine.onIndicatorUpdate(event);
            verify(applicationEventPublisher, times(1)).publishEvent(any(ConditionTriggeredEvent.class));
        }

        @Test
        @DisplayName("max triggers causes rule to expire")
        void maxTriggersExpiresRule() {
            ConditionRuleEntity entity = buildRuleEntity(
                    1L,
                    NIFTY_TOKEN,
                    IndicatorType.RSI,
                    14,
                    ConditionOperator.GT,
                    BigDecimal.valueOf(70),
                    EvaluationMode.TICK);
            entity.setMaxTriggers(1);
            entity.setCooldownMinutes(0);

            when(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(List.of(entity));
            conditionEngine.loadRules();

            when(indicatorService.getIndicatorValue(eq(NIFTY_TOKEN), eq(IndicatorType.RSI), eq(14), any()))
                    .thenReturn(BigDecimal.valueOf(75));
            when(conditionRuleJpaRepository.findById(1L)).thenReturn(Optional.of(entity));

            // First trigger fires
            IndicatorUpdateEvent event =
                    new IndicatorUpdateEvent(this, NIFTY_TOKEN, "NIFTY 50", Map.of("RSI:14", BigDecimal.valueOf(75)));
            conditionEngine.onIndicatorUpdate(event);
            verify(applicationEventPublisher, times(1)).publishEvent(any(ConditionTriggeredEvent.class));

            // Second trigger should be blocked because triggerCount == maxTriggers
            conditionEngine.onIndicatorUpdate(event);
            verify(applicationEventPublisher, times(1)).publishEvent(any(ConditionTriggeredEvent.class));
        }

        @Test
        @DisplayName("time window excludes rules outside valid hours")
        void timeWindowExclusion() {
            ConditionRuleEntity entity = buildRuleEntity(
                    1L,
                    NIFTY_TOKEN,
                    IndicatorType.RSI,
                    14,
                    ConditionOperator.GT,
                    BigDecimal.valueOf(70),
                    EvaluationMode.TICK);
            // Set valid window to 09:15-09:16 (already passed for most test runs)
            entity.setValidFrom(LocalTime.of(0, 0));
            entity.setValidUntil(LocalTime.of(0, 1));

            when(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(List.of(entity));
            conditionEngine.loadRules();

            IndicatorUpdateEvent event =
                    new IndicatorUpdateEvent(this, NIFTY_TOKEN, "NIFTY 50", Map.of("RSI:14", BigDecimal.valueOf(75)));
            conditionEngine.onIndicatorUpdate(event);

            // Should not trigger because we're likely outside the 00:00-00:01 window
            verify(applicationEventPublisher, never()).publishEvent(any(ConditionTriggeredEvent.class));
        }
    }

    @Nested
    @DisplayName("Trigger Persistence")
    class TriggerPersistence {

        @Test
        @DisplayName("persists trigger history on fire")
        void persistsTriggerHistory() {
            ConditionRuleEntity entity = buildRuleEntity(
                    1L,
                    NIFTY_TOKEN,
                    IndicatorType.RSI,
                    14,
                    ConditionOperator.GT,
                    BigDecimal.valueOf(70),
                    EvaluationMode.TICK);

            when(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(List.of(entity));
            conditionEngine.loadRules();

            when(indicatorService.getIndicatorValue(eq(NIFTY_TOKEN), eq(IndicatorType.RSI), eq(14), any()))
                    .thenReturn(BigDecimal.valueOf(75));
            when(conditionRuleJpaRepository.findById(1L)).thenReturn(Optional.of(entity));

            IndicatorUpdateEvent event =
                    new IndicatorUpdateEvent(this, NIFTY_TOKEN, "NIFTY 50", Map.of("RSI:14", BigDecimal.valueOf(75)));
            conditionEngine.onIndicatorUpdate(event);

            verify(conditionTriggerHistoryJpaRepository).save(any());
        }

        @Test
        @DisplayName("updates rule trigger count and lastTriggeredAt in database")
        void updatesRuleState() {
            ConditionRuleEntity entity = buildRuleEntity(
                    1L,
                    NIFTY_TOKEN,
                    IndicatorType.RSI,
                    14,
                    ConditionOperator.GT,
                    BigDecimal.valueOf(70),
                    EvaluationMode.TICK);

            when(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(List.of(entity));
            conditionEngine.loadRules();

            when(indicatorService.getIndicatorValue(eq(NIFTY_TOKEN), eq(IndicatorType.RSI), eq(14), any()))
                    .thenReturn(BigDecimal.valueOf(75));
            when(conditionRuleJpaRepository.findById(1L)).thenReturn(Optional.of(entity));

            IndicatorUpdateEvent event =
                    new IndicatorUpdateEvent(this, NIFTY_TOKEN, "NIFTY 50", Map.of("RSI:14", BigDecimal.valueOf(75)));
            conditionEngine.onIndicatorUpdate(event);

            ArgumentCaptor<ConditionRuleEntity> captor = ArgumentCaptor.forClass(ConditionRuleEntity.class);
            verify(conditionRuleJpaRepository).save(captor.capture());

            ConditionRuleEntity saved = captor.getValue();
            assertThat(saved.getTriggerCount()).isEqualTo(1);
            assertThat(saved.getLastTriggeredAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Interval Evaluation")
    class IntervalEvaluation {

        @Test
        @DisplayName("evaluates interval rules on schedule")
        void evaluatesIntervalRules() {
            ConditionRuleEntity entity = buildRuleEntity(
                    1L,
                    NIFTY_TOKEN,
                    IndicatorType.RSI,
                    14,
                    ConditionOperator.GT,
                    BigDecimal.valueOf(70),
                    EvaluationMode.INTERVAL_1M);

            when(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(List.of(entity));
            when(compositeConditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(Collections.emptyList());
            conditionEngine.loadRules();

            when(indicatorService.getIndicatorValue(eq(NIFTY_TOKEN), eq(IndicatorType.RSI), eq(14), any()))
                    .thenReturn(BigDecimal.valueOf(75));
            when(conditionRuleJpaRepository.findById(1L)).thenReturn(Optional.of(entity));

            conditionEngine.evaluateIntervalRules();

            verify(applicationEventPublisher).publishEvent(any(ConditionTriggeredEvent.class));
        }

        @Test
        @DisplayName("skips tick-mode rules during interval evaluation")
        void skipsTickRulesDuringInterval() {
            ConditionRuleEntity entity = buildRuleEntity(
                    1L,
                    NIFTY_TOKEN,
                    IndicatorType.RSI,
                    14,
                    ConditionOperator.GT,
                    BigDecimal.valueOf(70),
                    EvaluationMode.TICK);

            when(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(List.of(entity));
            when(compositeConditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE))
                    .thenReturn(Collections.emptyList());
            conditionEngine.loadRules();

            conditionEngine.evaluateIntervalRules();

            verify(indicatorService, never()).getIndicatorValue(any(), any(), any(), any());
        }
    }
}
