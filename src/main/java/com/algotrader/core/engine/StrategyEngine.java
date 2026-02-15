package com.algotrader.core.engine;

import com.algotrader.domain.enums.ActionType;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.AdjustmentAction;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.Tick;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.event.PositionEvent;
import com.algotrader.event.TickEvent;
import com.algotrader.exception.ResourceNotFoundException;
import com.algotrader.oms.JournaledMultiLegExecutor;
import com.algotrader.service.InstrumentService;
import com.algotrader.strategy.StrategyFactory;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.BaseStrategyConfig;
import com.algotrader.strategy.base.MarketSnapshot;
import com.algotrader.strategy.base.StrategyContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Central service managing all active trading strategy instances.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><b>Deployment:</b> Creates strategy instances via StrategyFactory, injects
 *       runtime services via StrategyContext, registers in the active map</li>
 *   <li><b>Lifecycle:</b> Arm, pause, resume, close individual strategies; pauseAll
 *       for emergency freeze without closing positions</li>
 *   <li><b>Tick routing:</b> Listens for TickEvents and routes market snapshots to
 *       ARMED/ACTIVE strategies matching the ticked underlying</li>
 *   <li><b>Position sync:</b> Listens for PositionEvents and routes to linked
 *       strategies via the positionToStrategies reverse index</li>
 *   <li><b>Force adjustment:</b> Allows manual adjustment bypass (cooldown skipped)</li>
 * </ul>
 *
 * <p><b>Concurrency model:</b> ConcurrentHashMap for the strategy registry.
 * Per-strategy ReadWriteLock protects lifecycle transitions (write lock) while
 * allowing concurrent tick evaluations (read lock via tryLock). Atomic
 * computeIfAbsent/computeIfPresent prevent race conditions during registration
 * and deregistration.
 *
 * <p><b>Tick routing priority:</b> This listener runs at @Order(4) per the
 * event ordering spec (after TickProcessor, IndicatorService, PositionService).
 */
@Service
public class StrategyEngine {

    private static final Logger log = LoggerFactory.getLogger(StrategyEngine.class);

    /** Active strategy instances keyed by strategy ID. */
    private final ConcurrentHashMap<String, BaseStrategy> activeStrategies = new ConcurrentHashMap<>();

    /** Per-strategy locks for lifecycle transitions vs tick evaluation. */
    private final ConcurrentHashMap<String, ReadWriteLock> strategyLocks = new ConcurrentHashMap<>();

    /**
     * Reverse index: positionId → set of strategyIds that have a leg linked to that position.
     * Enables O(1) lookup for position update routing instead of iterating all strategies.
     * Updated on adopt (registerPositionLink) and detach (unregisterPositionLink).
     */
    private final ConcurrentHashMap<String, Set<String>> positionToStrategies = new ConcurrentHashMap<>();

    private final StrategyFactory strategyFactory;
    private final EventPublisherHelper eventPublisherHelper;
    private final JournaledMultiLegExecutor journaledMultiLegExecutor;
    private final InstrumentService instrumentService;

    public StrategyEngine(
            StrategyFactory strategyFactory,
            EventPublisherHelper eventPublisherHelper,
            JournaledMultiLegExecutor journaledMultiLegExecutor,
            InstrumentService instrumentService) {
        this.strategyFactory = strategyFactory;
        this.eventPublisherHelper = eventPublisherHelper;
        this.journaledMultiLegExecutor = journaledMultiLegExecutor;
        this.instrumentService = instrumentService;
    }

    // ========================
    // DEPLOY
    // ========================

    /**
     * Deploys a new strategy instance. Creates the strategy, injects services,
     * and registers it in the active map. Returns the generated strategy ID.
     *
     * <p>Uses computeIfAbsent to atomically register the strategy and its lock,
     * preventing race conditions with concurrent deployments.
     *
     * @param type     the strategy type to deploy
     * @param name     user-provided name for this instance
     * @param config   strategy-specific configuration
     * @param autoArm  whether to immediately arm the strategy after deployment
     * @return the generated strategy ID
     */
    public String deployStrategy(StrategyType type, String name, BaseStrategyConfig config, boolean autoArm) {
        BaseStrategy strategy = strategyFactory.create(type, name, config);
        String strategyId = strategy.getId();

        StrategyContext context = buildContext();
        strategy.setServices(
                context.getEventPublisherHelper(),
                context.getJournaledMultiLegExecutor(),
                context.getInstrumentService());

        // Atomic registration: if ID collision (practically impossible with UUID), fail fast
        BaseStrategy existing = activeStrategies.putIfAbsent(strategyId, strategy);
        if (existing != null) {
            throw new IllegalStateException("Strategy ID collision: " + strategyId);
        }
        strategyLocks.put(strategyId, new ReentrantReadWriteLock());

        eventPublisherHelper.publishDecision(
                this, "DEPLOY", "Strategy deployed", strategyId, Map.of("type", type.name(), "name", name));

        if (autoArm) {
            armStrategy(strategyId);
        }

        // If immediate entry with fixed legs, execute entry right after arming
        if (config.isImmediateEntry() && strategy.getStatus() == StrategyStatus.ARMED) {
            withWriteLock(strategyId, () -> {
                strategy.executeImmediateEntry();
            });
        }

        return strategyId;
    }

    // ========================
    // LIFECYCLE
    // ========================

    /**
     * Arms a strategy, transitioning it from CREATED to ARMED.
     * The strategy will start monitoring for entry conditions.
     */
    public void armStrategy(String strategyId) {
        withWriteLock(strategyId, () -> {
            BaseStrategy strategy = getOrThrow(strategyId);
            StrategyStatus previous = strategy.getStatus();
            strategy.arm();
            eventPublisherHelper.publishDecision(
                    this, "LIFECYCLE", "Strategy armed", strategyId, Map.of("previousStatus", previous.name()));
        });
    }

    /**
     * Pauses a strategy. Stops all evaluation. Positions remain open.
     * Used for manual intervention without closing positions.
     */
    public void pauseStrategy(String strategyId) {
        withWriteLock(strategyId, () -> {
            BaseStrategy strategy = getOrThrow(strategyId);
            StrategyStatus previous = strategy.getStatus();
            strategy.pause();
            eventPublisherHelper.publishDecision(
                    this, "LIFECYCLE", "Strategy paused", strategyId, Map.of("previousStatus", previous.name()));
            // #TODO Task 7.2: Cancel in-flight orders for this strategy when pausing
        });
    }

    /**
     * Resumes a paused strategy back to ACTIVE.
     */
    public void resumeStrategy(String strategyId) {
        withWriteLock(strategyId, () -> {
            BaseStrategy strategy = getOrThrow(strategyId);
            StrategyStatus previous = strategy.getStatus();
            strategy.resume();
            eventPublisherHelper.publishDecision(
                    this, "LIFECYCLE", "Strategy resumed", strategyId, Map.of("previousStatus", previous.name()));
        });
    }

    /**
     * Initiates strategy close. Transitions to CLOSING and begins exit order execution.
     */
    public void closeStrategy(String strategyId) {
        withWriteLock(strategyId, () -> {
            BaseStrategy strategy = getOrThrow(strategyId);
            strategy.initiateClose();
        });
    }

    /**
     * Removes a CLOSED strategy from the active registry.
     * Only closed strategies can be undeployed.
     */
    public void undeployStrategy(String strategyId) {
        // Atomic removal: computeIfPresent ensures we only remove if present
        activeStrategies.computeIfPresent(strategyId, (id, strategy) -> {
            if (strategy.getStatus() != StrategyStatus.CLOSED) {
                throw new IllegalStateException(
                        "Cannot undeploy strategy in " + strategy.getStatus() + " state. Close it first.");
            }
            strategyLocks.remove(id);
            eventPublisherHelper.publishDecision(this, "DEPLOY", "Strategy undeployed", id, Map.of());
            return null; // removes from map
        });
    }

    /**
     * Pauses ALL active strategies (emergency freeze without closing positions).
     * Used by risk management and kill switch as a first-level response.
     */
    public void pauseAll() {
        List<String> paused = new ArrayList<>();
        for (Map.Entry<String, BaseStrategy> entry : activeStrategies.entrySet()) {
            BaseStrategy strategy = entry.getValue();
            if (strategy.getStatus() == StrategyStatus.ARMED || strategy.getStatus() == StrategyStatus.ACTIVE) {
                try {
                    pauseStrategy(entry.getKey());
                    paused.add(entry.getKey());
                } catch (Exception e) {
                    log.error("Failed to pause strategy {}: {}", entry.getKey(), e.getMessage());
                }
            }
        }
        eventPublisherHelper.publishDecision(
                this, "LIFECYCLE", "All strategies paused", null, Map.of("count", paused.size()));
    }

    // ========================
    // FORCE ADJUSTMENT
    // ========================

    /**
     * Forces a manual adjustment, bypassing the normal cooldown.
     * Called when the trader manually triggers an adjustment from the UI.
     *
     * @param strategyId the strategy to adjust
     * @param action     the adjustment action to execute
     */
    public void forceAdjustment(String strategyId, AdjustmentAction action) {
        withWriteLock(strategyId, () -> {
            BaseStrategy strategy = getOrThrow(strategyId);

            if (strategy.getStatus() != StrategyStatus.ACTIVE) {
                throw new IllegalStateException(
                        "Can only force-adjust ACTIVE strategies, current: " + strategy.getStatus());
            }

            eventPublisherHelper.publishDecision(
                    this,
                    "MANUAL_ADJUST",
                    "User forced adjustment: " + action.getType(),
                    strategyId,
                    Map.of("actionType", action.getType().name(), "parameters", action.getParameters()));

            // #TODO Tasks 6.3-6.6: Map ActionType to BaseStrategy adjustment helpers:
            //   ROLL_UP -> rollUp(), ROLL_DOWN -> rollDown(), ROLL_OUT -> rollOut(),
            //   ADD_HEDGE -> addHedge(), CLOSE_LEG -> closeLeg(), CLOSE_ALL -> initiateClose()
            //   REDUCE_SIZE -> partial close, ALERT_ONLY -> publish alert only
            if (action.getType() == ActionType.CLOSE_ALL) {
                strategy.initiateClose();
            }
        });
    }

    // ========================
    // TICK ROUTING
    // ========================

    /**
     * Routes tick events to strategies monitoring the ticked underlying.
     * Builds a MarketSnapshot from the tick and calls evaluate() on each
     * matching ARMED/ACTIVE strategy.
     *
     * <p>Uses tryLock to avoid blocking if a lifecycle transition is in progress.
     * A missed tick is acceptable -- the next tick will be evaluated.
     */
    @EventListener
    @Order(4) // After TickProcessor(1), IndicatorService(2), PositionService(3)
    public void onTick(TickEvent event) {
        Tick tick = event.getTick();
        MarketSnapshot snapshot = buildSnapshot(tick);

        for (Map.Entry<String, BaseStrategy> entry : activeStrategies.entrySet()) {
            String strategyId = entry.getKey();
            BaseStrategy strategy = entry.getValue();

            StrategyStatus status = strategy.getStatus();
            if (status != StrategyStatus.ARMED && status != StrategyStatus.ACTIVE) {
                continue;
            }

            // Only route ticks for the strategy's underlying
            // #TODO: Map instrumentToken -> underlying for multi-instrument strategies
            // For now, strategies will internally filter based on their underlying config

            ReadWriteLock lock = strategyLocks.get(strategyId);
            if (lock != null && lock.readLock().tryLock()) {
                try {
                    strategy.evaluate(snapshot);
                } catch (Exception e) {
                    log.error("Error evaluating strategy {} on tick: {}", strategyId, e.getMessage(), e);
                } finally {
                    lock.readLock().unlock();
                }
            }
            // If tryLock fails, a lifecycle transition is in progress -- skip this tick
        }
    }

    /**
     * Routes position updates to all strategies linked via strategy legs.
     * Uses the positionToStrategies reverse index for O(1) lookup instead of
     * iterating all active strategies.
     */
    @EventListener
    public void onPositionUpdate(PositionEvent event) {
        Position position = event.getPosition();
        String positionId = position.getId();

        Set<String> strategyIds = positionToStrategies.get(positionId);
        if (strategyIds == null || strategyIds.isEmpty()) {
            return;
        }

        for (String strategyId : strategyIds) {
            BaseStrategy strategy = activeStrategies.get(strategyId);
            if (strategy != null) {
                strategy.updatePosition(position);
            }
        }
    }

    // ========================
    // POSITION-STRATEGY INDEX
    // ========================

    /**
     * Registers a position→strategy link in the reverse index.
     * Called by PositionAdoptionService after a successful adopt.
     *
     * @param positionId the adopted position
     * @param strategyId the strategy that adopted it
     */
    public void registerPositionLink(String positionId, String strategyId) {
        positionToStrategies
                .computeIfAbsent(positionId, k -> ConcurrentHashMap.newKeySet())
                .add(strategyId);
        log.debug("Reverse index updated: position {} → added strategy {}", positionId, strategyId);
    }

    /**
     * Unregisters a position→strategy link from the reverse index.
     * Called by PositionAdoptionService after a successful detach.
     *
     * @param positionId the detached position
     * @param strategyId the strategy that detached it
     */
    public void unregisterPositionLink(String positionId, String strategyId) {
        Set<String> strategyIds = positionToStrategies.get(positionId);
        if (strategyIds != null) {
            strategyIds.remove(strategyId);
            if (strategyIds.isEmpty()) {
                positionToStrategies.remove(positionId);
            }
        }
        log.debug("Reverse index updated: position {} → removed strategy {}", positionId, strategyId);
    }

    /**
     * Populates the reverse index from persisted strategy legs.
     * Called on startup to rebuild the index from the database.
     *
     * @param positionStrategyLinks list of [positionId, strategyId] pairs from active legs
     */
    public void populatePositionIndex(List<Map.Entry<String, String>> positionStrategyLinks) {
        positionToStrategies.clear();
        for (Map.Entry<String, String> link : positionStrategyLinks) {
            registerPositionLink(link.getKey(), link.getValue());
        }
        log.info(
                "Reverse index populated with {} position→strategy links across {} positions",
                positionStrategyLinks.size(),
                positionToStrategies.size());
    }

    /**
     * Returns the set of strategy IDs linked to a position (for testing/diagnostics).
     */
    public Set<String> getStrategiesForPosition(String positionId) {
        Set<String> strategyIds = positionToStrategies.get(positionId);
        return strategyIds != null ? Collections.unmodifiableSet(strategyIds) : Set.of();
    }

    // ========================
    // QUERIES
    // ========================

    /**
     * Returns an unmodifiable view of all active strategies.
     */
    public Map<String, BaseStrategy> getActiveStrategies() {
        return Collections.unmodifiableMap(activeStrategies);
    }

    /**
     * Returns a specific strategy by ID.
     *
     * @throws ResourceNotFoundException if no strategy found
     */
    public BaseStrategy getStrategy(String strategyId) {
        return getOrThrow(strategyId);
    }

    /**
     * Returns all strategies of a specific type.
     */
    public List<BaseStrategy> getStrategiesByType(StrategyType type) {
        List<BaseStrategy> result = new ArrayList<>();
        for (BaseStrategy strategy : activeStrategies.values()) {
            if (strategy.getType() == type) {
                result.add(strategy);
            }
        }
        return result;
    }

    /**
     * Returns the last evaluation time for a strategy.
     * Used by frontend to display freshness of strategy state.
     */
    public LocalDateTime getLastEvaluationTime(String strategyId) {
        BaseStrategy strategy = activeStrategies.get(strategyId);
        return strategy != null ? strategy.getLastEvaluationTime() : null;
    }

    /**
     * Returns the total number of active (registered) strategies.
     */
    public int getActiveStrategyCount() {
        return activeStrategies.size();
    }

    // ========================
    // INTERNALS
    // ========================

    private StrategyContext buildContext() {
        return StrategyContext.builder()
                .eventPublisherHelper(eventPublisherHelper)
                .journaledMultiLegExecutor(journaledMultiLegExecutor)
                .instrumentService(instrumentService)
                .build();
    }

    private MarketSnapshot buildSnapshot(Tick tick) {
        return MarketSnapshot.builder()
                .spotPrice(tick.getLastPrice())
                .atmIV(null) // #TODO Phase 3.4b: Populate from IV service
                .timestamp(tick.getTimestamp() != null ? tick.getTimestamp() : LocalDateTime.now())
                .build();
    }

    private void withWriteLock(String strategyId, Runnable action) {
        ReadWriteLock lock = strategyLocks.get(strategyId);
        if (lock == null) {
            throw new ResourceNotFoundException("Strategy", strategyId);
        }

        lock.writeLock().lock();
        try {
            action.run();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private BaseStrategy getOrThrow(String strategyId) {
        BaseStrategy strategy = activeStrategies.get(strategyId);
        if (strategy == null) {
            throw new ResourceNotFoundException("Strategy", strategyId);
        }
        return strategy;
    }
}
