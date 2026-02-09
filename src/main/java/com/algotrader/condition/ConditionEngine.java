package com.algotrader.condition;

import com.algotrader.domain.enums.ConditionRuleStatus;
import com.algotrader.domain.enums.EvaluationMode;
import com.algotrader.domain.model.CompositeConditionRule;
import com.algotrader.domain.model.ConditionRule;
import com.algotrader.entity.ConditionRuleEntity;
import com.algotrader.entity.ConditionTriggerHistoryEntity;
import com.algotrader.event.ConditionTriggeredEvent;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.event.IndicatorUpdateEvent;
import com.algotrader.indicator.IndicatorService;
import com.algotrader.mapper.ConditionRuleMapper;
import com.algotrader.repository.jpa.CompositeConditionRuleJpaRepository;
import com.algotrader.repository.jpa.ConditionRuleJpaRepository;
import com.algotrader.repository.jpa.ConditionTriggerHistoryJpaRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.mapstruct.factory.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Core service for condition-based auto-deployment of trading strategies.
 *
 * <p>Evaluates user-defined rules against real-time technical indicator values
 * and triggers actions (deploy strategy, arm strategy, or alert) when conditions
 * are met. This is the bridge between market analysis (via IndicatorService) and
 * strategy execution (via StrategyEngine).
 *
 * <p><b>Architecture:</b>
 * <ul>
 *   <li>Active rules are cached in-memory in a ConcurrentHashMap keyed by instrument
 *       token for O(1) lookup on tick events</li>
 *   <li>Tick-level rules (EvaluationMode.TICK) are evaluated on every IndicatorUpdateEvent</li>
 *   <li>Interval rules (1M/5M/15M) are evaluated by a @Scheduled method every 60 seconds</li>
 *   <li>Crossing operators (CROSSES_ABOVE/CROSSES_BELOW) track previous indicator values</li>
 *   <li>Each trigger is persisted to condition_trigger_history for audit</li>
 * </ul>
 *
 * <p><b>Concurrency:</b> Rule evaluation is read-only except for trigger handling.
 * The handleTrigger method synchronizes on the rule ID to prevent double-triggers
 * from concurrent tick and interval evaluations.
 */
@Service
@EnableConfigurationProperties(ConditionEngineConfig.class)
public class ConditionEngine {

    private static final Logger log = LoggerFactory.getLogger(ConditionEngine.class);

    private final ConditionEngineConfig conditionEngineConfig;
    private final ConditionRuleJpaRepository conditionRuleJpaRepository;
    private final CompositeConditionRuleJpaRepository compositeConditionRuleJpaRepository;
    private final ConditionTriggerHistoryJpaRepository conditionTriggerHistoryJpaRepository;
    private final IndicatorService indicatorService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final EventPublisherHelper eventPublisherHelper;

    private final ConditionRuleMapper conditionRuleMapper = Mappers.getMapper(ConditionRuleMapper.class);

    /** In-memory cache of active rules, grouped by instrument token. */
    private final Map<Long, List<ConditionRule>> rulesByInstrument = new ConcurrentHashMap<>();

    /** Previous indicator values for CROSSES_ABOVE/CROSSES_BELOW detection. Key: "ruleId:indicatorType". */
    private final Map<String, BigDecimal> previousValues = new ConcurrentHashMap<>();

    /** Lock objects per rule ID for atomic trigger handling. */
    private final Map<Long, Object> triggerLocks = new ConcurrentHashMap<>();

    public ConditionEngine(
            ConditionEngineConfig conditionEngineConfig,
            ConditionRuleJpaRepository conditionRuleJpaRepository,
            CompositeConditionRuleJpaRepository compositeConditionRuleJpaRepository,
            ConditionTriggerHistoryJpaRepository conditionTriggerHistoryJpaRepository,
            IndicatorService indicatorService,
            ApplicationEventPublisher applicationEventPublisher,
            EventPublisherHelper eventPublisherHelper) {
        this.conditionEngineConfig = conditionEngineConfig;
        this.conditionRuleJpaRepository = conditionRuleJpaRepository;
        this.compositeConditionRuleJpaRepository = compositeConditionRuleJpaRepository;
        this.conditionTriggerHistoryJpaRepository = conditionTriggerHistoryJpaRepository;
        this.indicatorService = indicatorService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.eventPublisherHelper = eventPublisherHelper;
    }

    /**
     * Loads all active rules from the database into the in-memory cache.
     * Called at startup and whenever rules are created/updated/deleted via REST API.
     */
    @jakarta.annotation.PostConstruct
    public void loadRules() {
        if (!conditionEngineConfig.isEnabled()) {
            log.info("Condition engine is disabled");
            return;
        }

        List<ConditionRuleEntity> activeEntities = conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE);
        List<ConditionRule> activeRules = conditionRuleMapper.toDomainList(activeEntities);

        rulesByInstrument.clear();
        triggerLocks.clear();

        for (ConditionRule rule : activeRules) {
            rulesByInstrument
                    .computeIfAbsent(rule.getInstrumentToken(), k -> new CopyOnWriteArrayList<>())
                    .add(rule);
            triggerLocks.put(rule.getId(), new Object());
        }

        log.info("Loaded {} active condition rules for {} instruments", activeRules.size(), rulesByInstrument.size());
    }

    /**
     * Evaluates tick-level rules when indicator values are updated.
     * Triggered by IndicatorUpdateEvent (published when a bar completes).
     */
    @EventListener
    public void onIndicatorUpdate(IndicatorUpdateEvent event) {
        if (!conditionEngineConfig.isEnabled() || !conditionEngineConfig.isTickEvaluation()) {
            return;
        }

        Long instrumentToken = event.getInstrumentToken();
        List<ConditionRule> rules = rulesByInstrument.get(instrumentToken);
        if (rules == null || rules.isEmpty()) {
            return;
        }

        for (ConditionRule rule : rules) {
            if (rule.getEvaluationMode() == EvaluationMode.TICK) {
                evaluateRule(rule);
            }
        }
    }

    /**
     * Evaluates interval-based rules on a scheduled basis.
     * Runs every 60 seconds and checks which rules should be evaluated
     * based on their evaluation mode.
     */
    @Scheduled(fixedRateString = "${condition-engine.interval-check-ms:60000}")
    public void evaluateIntervalRules() {
        if (!conditionEngineConfig.isEnabled()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        for (List<ConditionRule> rules : rulesByInstrument.values()) {
            for (ConditionRule rule : rules) {
                if (shouldEvaluateInterval(rule, now)) {
                    evaluateRule(rule);
                }
            }
        }

        // Evaluate composite rules
        evaluateCompositeRules();
    }

    /**
     * Returns the current rule count per status for monitoring.
     */
    public int getActiveRuleCount() {
        return rulesByInstrument.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Returns the in-memory rules for an instrument (for testing/debugging).
     */
    public List<ConditionRule> getRulesForInstrument(Long instrumentToken) {
        return rulesByInstrument.getOrDefault(instrumentToken, List.of());
    }

    // ---- Internal ----

    private boolean shouldEvaluateInterval(ConditionRule rule, LocalDateTime now) {
        if (rule.getStatus() != ConditionRuleStatus.ACTIVE) {
            return false;
        }

        return switch (rule.getEvaluationMode()) {
            case INTERVAL_1M -> true;
            case INTERVAL_5M -> now.getMinute() % 5 == 0;
            case INTERVAL_15M -> now.getMinute() % 15 == 0;
            default -> false; // TICK is handled by onIndicatorUpdate
        };
    }

    private void evaluateRule(ConditionRule rule) {
        if (!isRuleEvaluable(rule)) {
            return;
        }

        BigDecimal currentValue = indicatorService.getIndicatorValue(
                rule.getInstrumentToken(),
                rule.getIndicatorType(),
                rule.getIndicatorPeriod(),
                rule.getIndicatorField());

        if (currentValue == null) {
            log.debug(
                    "No indicator value for rule {}: {} {} on token {}",
                    rule.getId(),
                    rule.getIndicatorType(),
                    rule.getIndicatorPeriod(),
                    rule.getInstrumentToken());
            return;
        }

        boolean triggered = evaluateCondition(rule, currentValue);

        // Log the evaluation for observability
        eventPublisherHelper.publishDecision(
                this,
                "CONDITION",
                "Rule evaluated: " + rule.getIndicatorType() + "(" + rule.getIndicatorPeriod() + ") = "
                        + currentValue + " " + rule.getOperator() + " " + rule.getThresholdValue()
                        + " -> " + triggered,
                null,
                Map.of(
                        "ruleId", rule.getId(),
                        "ruleName", rule.getName(),
                        "triggered", triggered));

        if (triggered) {
            handleTrigger(rule, currentValue);
        }

        // Update previous value for crossing detection
        String key = rule.getId() + ":" + rule.getIndicatorType();
        previousValues.put(key, currentValue);
    }

    private boolean isRuleEvaluable(ConditionRule rule) {
        if (rule.getStatus() != ConditionRuleStatus.ACTIVE) {
            return false;
        }

        // Check time window
        LocalTime now = LocalTime.now();
        if (rule.getValidFrom() != null && now.isBefore(rule.getValidFrom())) {
            return false;
        }
        if (rule.getValidUntil() != null && now.isAfter(rule.getValidUntil())) {
            return false;
        }

        // Check max triggers
        if (rule.getMaxTriggers() != null && rule.getTriggerCount() >= rule.getMaxTriggers()) {
            rule.setStatus(ConditionRuleStatus.TRIGGERED);
            persistRuleStatus(rule);
            return false;
        }

        // Check cooldown
        if (rule.getLastTriggeredAt() != null && rule.getCooldownMinutes() != null) {
            Duration elapsed = Duration.between(rule.getLastTriggeredAt(), LocalDateTime.now());
            if (elapsed.toMinutes() < rule.getCooldownMinutes()) {
                return false;
            }
        }

        return true;
    }

    public boolean evaluateCondition(ConditionRule rule, BigDecimal currentValue) {
        BigDecimal threshold = rule.getThresholdValue();

        return switch (rule.getOperator()) {
            case GT -> currentValue.compareTo(threshold) > 0;
            case LT -> currentValue.compareTo(threshold) < 0;
            case GTE -> currentValue.compareTo(threshold) >= 0;
            case LTE -> currentValue.compareTo(threshold) <= 0;
            case CROSSES_ABOVE -> {
                String key = rule.getId() + ":" + rule.getIndicatorType();
                BigDecimal previous = previousValues.get(key);
                yield previous != null && previous.compareTo(threshold) < 0 && currentValue.compareTo(threshold) >= 0;
            }
            case CROSSES_BELOW -> {
                String key = rule.getId() + ":" + rule.getIndicatorType();
                BigDecimal previous = previousValues.get(key);
                yield previous != null && previous.compareTo(threshold) > 0 && currentValue.compareTo(threshold) <= 0;
            }
            case BETWEEN -> {
                BigDecimal secondary = rule.getSecondaryThreshold();
                yield secondary != null
                        && currentValue.compareTo(threshold) >= 0
                        && currentValue.compareTo(secondary) <= 0;
            }
            case OUTSIDE -> {
                BigDecimal secondary = rule.getSecondaryThreshold();
                yield secondary != null
                        && (currentValue.compareTo(threshold) < 0 || currentValue.compareTo(secondary) > 0);
            }
        };
    }

    private void handleTrigger(ConditionRule rule, BigDecimal indicatorValue) {
        // Synchronize on rule-specific lock to prevent double-triggers
        Object lock = triggerLocks.computeIfAbsent(rule.getId(), k -> new Object());
        synchronized (lock) {
            // Re-check evaluability inside the lock (trigger count may have changed)
            if (rule.getMaxTriggers() != null && rule.getTriggerCount() >= rule.getMaxTriggers()) {
                return;
            }

            log.info(
                    "Condition rule triggered: {} ({} {} {} = {})",
                    rule.getName(),
                    rule.getIndicatorType(),
                    rule.getOperator(),
                    rule.getThresholdValue(),
                    indicatorValue);

            // Update trigger state
            rule.setTriggerCount(rule.getTriggerCount() + 1);
            rule.setLastTriggeredAt(LocalDateTime.now());
            persistRuleStatus(rule);

            // Persist trigger history
            persistTriggerHistory(rule, indicatorValue, null);

            // Publish event for WebSocket notification and decision logging
            applicationEventPublisher.publishEvent(new ConditionTriggeredEvent(this, rule, indicatorValue));

            // Execute action
            executeAction(rule);
        }
    }

    private void executeAction(ConditionRule rule) {
        switch (rule.getActionType()) {
            case DEPLOY_STRATEGY -> {
                // #TODO Phase 13 follow-up: Wire to StrategyEngine.deployStrategy()
                // For now, log the intended deployment
                log.info(
                        "Would deploy strategy: type={}, underlying={}, config={}",
                        rule.getStrategyType(),
                        rule.getUnderlying(),
                        rule.getStrategyConfig());
                eventPublisherHelper.publishDecision(
                        this,
                        "CONDITION",
                        "Strategy deployment triggered (awaiting StrategyEngine integration)",
                        null,
                        Map.of("ruleId", rule.getId(), "strategyType", String.valueOf(rule.getStrategyType())));
            }
            case ARM_STRATEGY -> {
                // #TODO Phase 13 follow-up: Wire to StrategyEngine.deployStrategy() with autoArm=true
                log.info(
                        "Would deploy and arm strategy: type={}, underlying={}, config={}",
                        rule.getStrategyType(),
                        rule.getUnderlying(),
                        rule.getStrategyConfig());
                eventPublisherHelper.publishDecision(
                        this,
                        "CONDITION",
                        "Strategy deploy+arm triggered (awaiting StrategyEngine integration)",
                        null,
                        Map.of("ruleId", rule.getId(), "strategyType", String.valueOf(rule.getStrategyType())));
            }
            case ALERT_ONLY -> {
                log.info("Alert-only rule triggered: {}", rule.getName());
                eventPublisherHelper.publishDecision(
                        this,
                        "CONDITION",
                        "Alert: " + rule.getName() + " triggered",
                        null,
                        Map.of("ruleId", rule.getId(), "ruleName", rule.getName()));
            }
        }
    }

    private void evaluateCompositeRules() {
        List<CompositeConditionRule> compositeRules = conditionRuleMapper.toCompositeDomainList(
                compositeConditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE));

        for (CompositeConditionRule composite : compositeRules) {
            List<ConditionRule> children = conditionRuleMapper.toDomainList(
                    conditionRuleJpaRepository.findAllById(composite.getChildRuleIds()));

            boolean result = evaluateComposite(composite, children);

            if (result) {
                log.info("Composite rule triggered: {} ({})", composite.getName(), composite.getLogicOperator());
                // #TODO: Handle composite trigger (similar to individual rule trigger)
                eventPublisherHelper.publishDecision(
                        this,
                        "CONDITION",
                        "Composite rule triggered: " + composite.getName(),
                        null,
                        Map.of(
                                "compositeId",
                                composite.getId(),
                                "logic",
                                composite.getLogicOperator().name()));
            }
        }
    }

    private boolean evaluateComposite(CompositeConditionRule composite, List<ConditionRule> children) {
        if (children.isEmpty()) {
            return false;
        }

        List<Boolean> results = children.stream()
                .map(child -> {
                    BigDecimal value = indicatorService.getIndicatorValue(
                            child.getInstrumentToken(),
                            child.getIndicatorType(),
                            child.getIndicatorPeriod(),
                            child.getIndicatorField());
                    if (value == null) {
                        return false;
                    }
                    return evaluateCondition(child, value);
                })
                .toList();

        return switch (composite.getLogicOperator()) {
            case AND -> results.stream().allMatch(r -> r);
            case OR -> results.stream().anyMatch(r -> r);
        };
    }

    private void persistRuleStatus(ConditionRule rule) {
        conditionRuleJpaRepository.findById(rule.getId()).ifPresent(entity -> {
            entity.setStatus(rule.getStatus());
            entity.setTriggerCount(rule.getTriggerCount());
            entity.setLastTriggeredAt(rule.getLastTriggeredAt());
            entity.setUpdatedAt(LocalDateTime.now());
            conditionRuleJpaRepository.save(entity);
        });
    }

    private void persistTriggerHistory(ConditionRule rule, BigDecimal indicatorValue, String strategyId) {
        ConditionTriggerHistoryEntity historyEntity = ConditionTriggerHistoryEntity.builder()
                .ruleId(rule.getId())
                .ruleName(rule.getName())
                .indicatorType(rule.getIndicatorType().name())
                .indicatorValue(indicatorValue)
                .thresholdValue(rule.getThresholdValue())
                .operator(rule.getOperator().name())
                .actionTaken(rule.getActionType().name())
                .strategyId(strategyId)
                .triggeredAt(LocalDateTime.now())
                .build();
        conditionTriggerHistoryJpaRepository.save(historyEntity);
    }
}
