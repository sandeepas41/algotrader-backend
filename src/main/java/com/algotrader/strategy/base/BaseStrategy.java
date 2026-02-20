package com.algotrader.strategy.base;

import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Instrument;
import com.algotrader.domain.model.NewLegDefinition;
import com.algotrader.domain.model.Position;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.oms.JournaledMultiLegExecutor;
import com.algotrader.oms.OrderRequest;
import com.algotrader.service.InstrumentService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.StampedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for all trading strategies in the system.
 *
 * <p>Provides the common infrastructure that every strategy needs:
 * <ul>
 *   <li><b>Evaluation loop:</b> Enforces monitoring interval, stale data guard,
 *       and auto-pause thresholds before delegating to subclass logic</li>
 *   <li><b>Exit evaluation:</b> Target profit, max loss, and DTE-based exits
 *       (subclasses can override {@link #shouldExit} for custom logic)</li>
 *   <li><b>Position tracking:</b> Maintains a list of positions, calculates
 *       aggregate P&L and net delta</li>
 *   <li><b>Adjustment cooldown:</b> Configurable per-strategy cooldown prevents
 *       rapid-fire adjustments</li>
 *   <li><b>Concurrency safety:</b> Uses {@link StampedLock} with optimistic reads
 *       for tick evaluation (fast path) and write locks for state changes</li>
 *   <li><b>Decision logging:</b> Every evaluation, skip, and lifecycle change is
 *       logged via {@link EventPublisherHelper#publishDecision}</li>
 * </ul>
 *
 * <p>Subclasses implement the four abstract methods: {@link #shouldEnter},
 * {@link #buildEntryOrders}, {@link #shouldExit}, and {@link #adjust}.
 * Everything else (timing, guards, P&L, concurrency) is handled here.
 *
 * <p><b>Lock contention mitigation:</b> The evaluate() method acquires an optimistic
 * read stamp for condition checks, then releases it before executing orders (which
 * involve blocking I/O). Multi-leg execution runs outside the lock. Lifecycle
 * transitions (arm, pause, close) acquire the write lock.
 */
public abstract class BaseStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(BaseStrategy.class);

    /** Default stale data threshold: 5 seconds for positional strategies. */
    private static final Duration DEFAULT_STALE_DATA_THRESHOLD = Duration.ofSeconds(5);

    /** Default adjustment cooldown: 5 minutes for positional strategies. */
    private static final Duration DEFAULT_ADJUSTMENT_COOLDOWN = Duration.ofMinutes(5);

    /** Default timeout for waiting on BUY fills in buy-first-then-sell mode. */
    private static final Duration BUY_FILL_TIMEOUT = Duration.ofSeconds(30);

    // ---- Identity ----
    protected final String id;
    protected final String name;
    protected final BaseStrategyConfig config;

    // ---- State ----
    protected volatile StrategyStatus status = StrategyStatus.CREATED;
    protected final List<Position> positions = new ArrayList<>();
    protected volatile BigDecimal entryPremium;
    protected volatile LocalDateTime lastEvaluationTime;
    protected volatile LocalDateTime entryTime;
    protected volatile LocalDateTime lastAdjustmentTime;

    // ---- Concurrency ----
    /**
     * StampedLock for concurrent strategy access.
     * Optimistic reads for tick evaluation (no lock contention in fast path).
     * Write lock for lifecycle transitions (arm, pause, close, position updates).
     */
    protected final StampedLock stampedLock = new StampedLock();

    // ---- Services (injected via setServices) ----
    protected EventPublisherHelper eventPublisherHelper;
    protected JournaledMultiLegExecutor journaledMultiLegExecutor;
    protected InstrumentService instrumentService;

    protected BaseStrategy(String id, String name, BaseStrategyConfig config) {
        this.id = id;
        this.name = name;
        this.config = config;
    }

    /**
     * Injects runtime services. Called by StrategyEngine after construction.
     * Kept separate from constructor to avoid circular dependencies with Spring beans.
     */
    public void setServices(
            EventPublisherHelper eventPublisherHelper,
            JournaledMultiLegExecutor journaledMultiLegExecutor,
            InstrumentService instrumentService) {
        this.eventPublisherHelper = eventPublisherHelper;
        this.journaledMultiLegExecutor = journaledMultiLegExecutor;
        this.instrumentService = instrumentService;
    }

    // ========================
    // ABSTRACT METHODS (each strategy type implements these)
    // ========================

    /** Evaluate whether to enter. Called when ARMED. */
    protected abstract boolean shouldEnter(MarketSnapshot snapshot);

    /** Build entry orders based on current market. Called after shouldEnter returns true. */
    protected abstract List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot);

    /** Evaluate whether to exit. Called when ACTIVE. Subclass can add custom conditions. */
    protected abstract boolean shouldExit(MarketSnapshot snapshot);

    /** Evaluate and execute adjustments. Called when ACTIVE and not in cooldown. */
    protected abstract void adjust(MarketSnapshot snapshot);

    /** Which strategy types this can morph into. */
    @Override
    public abstract List<StrategyType> supportedMorphs();

    // ========================
    // IDENTITY (TradingStrategy interface)
    // ========================

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public StrategyStatus getStatus() {
        return status;
    }

    @Override
    public String getUnderlying() {
        return config.getUnderlying();
    }

    /** Returns the strategy configuration (expiry, lots, exit params, etc.). */
    public BaseStrategyConfig getConfig() {
        return config;
    }

    @Override
    public Duration getStaleDataThreshold() {
        return DEFAULT_STALE_DATA_THRESHOLD;
    }

    @Override
    public Duration getAdjustmentCooldown() {
        return DEFAULT_ADJUSTMENT_COOLDOWN;
    }

    @Override
    public BigDecimal getEntryPremium() {
        return entryPremium;
    }

    /**
     * Sets the entry premium. Used by PositionAdoptionService to recalculate
     * entry premium when positions are adopted or detached.
     */
    public void setEntryPremium(BigDecimal entryPremium) {
        this.entryPremium = entryPremium;
    }

    @Override
    public LocalDateTime getLastEvaluationTime() {
        return lastEvaluationTime;
    }

    @Override
    public LocalDateTime getEntryTime() {
        return entryTime;
    }

    @Override
    public BigDecimal getAutoPausePnlThreshold() {
        return config.getAutoPausePnlThreshold();
    }

    @Override
    public BigDecimal getAutoPauseDeltaThreshold() {
        return config.getAutoPauseDeltaThreshold();
    }

    // ========================
    // LIFECYCLE
    // ========================

    @Override
    public void arm() {
        long stamp = stampedLock.writeLock();
        try {
            StrategyStatus previous = this.status;
            this.status = StrategyStatus.ARMED;
            logDecision("LIFECYCLE", "Strategy armed", Map.of("previousStatus", previous.name()));
        } finally {
            stampedLock.unlockWrite(stamp);
        }
    }

    @Override
    public void pause() {
        long stamp = stampedLock.writeLock();
        try {
            StrategyStatus previous = this.status;
            this.status = StrategyStatus.PAUSED;
            logDecision("LIFECYCLE", "Strategy paused", Map.of("previousStatus", previous.name()));
        } finally {
            stampedLock.unlockWrite(stamp);
        }
    }

    @Override
    public void resume() {
        long stamp = stampedLock.writeLock();
        try {
            StrategyStatus previous = this.status;
            this.status = StrategyStatus.ACTIVE;
            logDecision("LIFECYCLE", "Strategy resumed", Map.of("previousStatus", previous.name()));
        } finally {
            stampedLock.unlockWrite(stamp);
        }
    }

    /**
     * Directly activates the strategy with adopted positions, bypassing the normal
     * entry flow (ARMED → shouldEnter → buildEntryOrders → ACTIVE). Used when creating
     * a strategy from existing broker positions — no new orders are placed.
     *
     * <p>Sets status to ACTIVE and records entryTime. Only valid from CREATED or ARMED state.
     */
    public void activateWithAdoptedPositions() {
        long stamp = stampedLock.writeLock();
        try {
            StrategyStatus previous = this.status;
            if (previous != StrategyStatus.CREATED && previous != StrategyStatus.ARMED) {
                throw new IllegalStateException(
                        "Can only activate with adopted positions from CREATED or ARMED, current: " + previous);
            }
            this.status = StrategyStatus.ACTIVE;
            this.entryTime = LocalDateTime.now();
            logDecision(
                    "LIFECYCLE",
                    "Strategy activated with adopted positions",
                    Map.of("previousStatus", previous.name(), "positions", positions.size()));
        } finally {
            stampedLock.unlockWrite(stamp);
        }
    }

    @Override
    public void initiateClose() {
        List<OrderRequest> exitOrders;

        long stamp = stampedLock.writeLock();
        try {
            this.status = StrategyStatus.CLOSING;
            logDecision("LIFECYCLE", "Closing initiated", Map.of("positions", positions.size()));
            exitOrders = buildExitOrders();
        } finally {
            stampedLock.unlockWrite(stamp);
        }

        // Execute exit orders OUTSIDE the lock to avoid holding it during I/O
        // Buy-first: buying back short positions frees margin before selling long positions
        if (!exitOrders.isEmpty() && journaledMultiLegExecutor != null) {
            JournaledMultiLegExecutor.MultiLegResult result = journaledMultiLegExecutor.executeBuyFirstThenSell(
                    exitOrders, id, "EXIT", OrderPriority.STRATEGY_EXIT, BUY_FILL_TIMEOUT);

            if (!result.isSuccess()) {
                logDecision(
                        "EXIT_FAILED",
                        "Exit order execution failed, manual intervention needed",
                        Map.of("groupId", result.getGroupId()));
            }
        }
    }

    /**
     * Builds exit orders for all current positions. Each position gets an opposite
     * order (BUY for short positions, SELL for long positions) to close it.
     */
    protected List<OrderRequest> buildExitOrders() {
        List<OrderRequest> exitOrders = new ArrayList<>();
        for (Position pos : positions) {
            if (pos.getQuantity() == 0 || pos.getInstrumentToken() == null) continue;
            exitOrders.add(OrderRequest.builder()
                    .instrumentToken(pos.getInstrumentToken())
                    .tradingSymbol(pos.getTradingSymbol())
                    .exchange(pos.getExchange())
                    .side(pos.getQuantity() > 0 ? OrderSide.SELL : OrderSide.BUY)
                    .type(OrderType.MARKET)
                    .quantity(Math.abs(pos.getQuantity()))
                    .strategyId(id)
                    .build());
        }
        return exitOrders;
    }

    // ========================
    // IMMEDIATE ENTRY (from FE-sent leg configs)
    // ========================

    /**
     * Executes entry orders immediately from FE-sent leg configs, bypassing
     * the tick-driven shouldEnter/buildEntryOrders loop. Called by StrategyEngine
     * when config.immediateEntry is true (all FIXED legs + LIVE trading mode).
     *
     * <p>Each leg is resolved to a Kite instrument via InstrumentService for
     * proper tradingSymbol + instrumentToken. On success, transitions to ACTIVE.
     *
     * @return the executed OrderRequests (for leg creation + position linking), or empty list on failure
     */
    public List<OrderRequest> executeImmediateEntry() {
        List<NewLegDefinition> legDefs = config.getLegConfigs();
        if (legDefs == null || legDefs.isEmpty()) {
            logDecision("ENTRY_SKIP", "No leg configs for immediate entry", Map.of());
            return List.of();
        }

        List<OrderRequest> orders = buildOrdersFromLegConfigs(legDefs);
        if (orders.isEmpty()) {
            logDecision("ENTRY_SKIP", "No orders built from leg configs", Map.of());
            return List.of();
        }

        logDecision(
                "ENTRY_EXEC",
                "Executing immediate entry",
                Map.of("legs", orders.size(), "buyFirst", config.isBuyFirst()));

        // Choose execution mode: buy-first-then-sell for margin benefit, or parallel
        JournaledMultiLegExecutor.MultiLegResult result;
        if (config.isBuyFirst()) {
            result = journaledMultiLegExecutor.executeBuyFirstThenSell(
                    orders, id, "ENTRY", OrderPriority.STRATEGY_ENTRY, BUY_FILL_TIMEOUT);
        } else {
            result = journaledMultiLegExecutor.executeParallel(orders, id, "ENTRY", OrderPriority.STRATEGY_ENTRY);
        }

        if (!result.isSuccess()) {
            logDecision(
                    "ENTRY_FAILED", "Immediate entry failed, staying ARMED", Map.of("groupId", result.getGroupId()));
            return List.of();
        }

        long stamp = stampedLock.writeLock();
        try {
            this.status = StrategyStatus.ACTIVE;
            this.entryTime = LocalDateTime.now();
        } finally {
            stampedLock.unlockWrite(stamp);
        }

        return orders;
    }

    /**
     * Builds OrderRequests from FE-sent leg definitions. Each leg is resolved
     * to a Kite instrument via InstrumentService for proper tradingSymbol + token.
     * Quantity = leg.lots * config.lots * instrument.lotSize.
     *
     * <p>Returns an empty list if any instrument cannot be resolved (all-or-nothing).
     */
    private List<OrderRequest> buildOrdersFromLegConfigs(List<NewLegDefinition> legDefs) {
        List<OrderRequest> orders = new ArrayList<>();
        LocalDate expiry = config.getExpiry();
        String underlying = config.getUnderlying();

        for (NewLegDefinition leg : legDefs) {
            Optional<Instrument> instrumentOpt =
                    instrumentService.resolveOption(underlying, expiry, leg.getStrike(), leg.getOptionType());

            if (instrumentOpt.isEmpty()) {
                logDecision(
                        "ENTRY_ERROR",
                        "Could not resolve instrument",
                        Map.of(
                                "strike", leg.getStrike().toString(),
                                "type", leg.getOptionType().name(),
                                "underlying", underlying,
                                "expiry", expiry.toString()));
                // Fail all legs if any instrument can't be resolved
                return List.of();
            }

            Instrument instrument = instrumentOpt.get();
            int legLots = leg.getLots() != null ? leg.getLots() : 1;
            int quantity = legLots * config.getLots() * instrument.getLotSize();

            orders.add(OrderRequest.builder()
                    .instrumentToken(instrument.getToken())
                    .tradingSymbol(instrument.getTradingSymbol())
                    .exchange(instrument.getExchange())
                    .side(leg.getSide())
                    .type(OrderType.MARKET)
                    .quantity(quantity)
                    .strategyId(id)
                    .build());
        }
        return orders;
    }

    // ========================
    // CORE EVALUATION LOOP
    // ========================

    /**
     * Main evaluation entry point. Called by StrategyEngine on tick or interval.
     *
     * <p>Uses optimistic read for the fast path (checking interval, stale data,
     * auto-pause). If a state change is needed (entry, exit), it escalates to
     * a write lock. Multi-leg execution runs outside any lock.
     */
    @Override
    public void evaluate(MarketSnapshot snapshot) {
        // Optimistic read: check if we should evaluate at all
        long stamp = stampedLock.tryOptimisticRead();

        boolean shouldEval = shouldEvaluateNow();
        boolean stale = isDataStale();
        StrategyStatus currentStatus = this.status;

        if (!stampedLock.validate(stamp)) {
            // Concurrent write happened -- acquire read lock and retry
            stamp = stampedLock.readLock();
            try {
                shouldEval = shouldEvaluateNow();
                stale = isDataStale();
                currentStatus = this.status;
            } finally {
                stampedLock.unlockRead(stamp);
            }
        }

        if (!shouldEval) {
            return;
        }

        if (stale) {
            logDecision(
                    "SKIP",
                    "Stale data detected",
                    Map.of("threshold", getStaleDataThreshold().toString()));
            return;
        }

        // Check auto-pause thresholds
        if (currentStatus == StrategyStatus.ACTIVE && shouldAutoPause()) {
            pause();
            return;
        }

        this.lastEvaluationTime = LocalDateTime.now();

        switch (currentStatus) {
            case ARMED -> evaluateEntry(snapshot);
            case ACTIVE -> {
                evaluateExit(snapshot);
                // Exit might have changed status -- don't evaluate adjustments if no longer ACTIVE
                if (this.status == StrategyStatus.ACTIVE) {
                    evaluateAdjustments(snapshot);
                }
            }
            default -> {
                // CREATED, PAUSED, MORPHING, CLOSING, CLOSED -- no evaluation
            }
        }
    }

    // ========================
    // ENTRY
    // ========================

    private void evaluateEntry(MarketSnapshot snapshot) {
        boolean enter = shouldEnter(snapshot);

        logDecision(
                "ENTRY_EVAL",
                enter ? "Entry conditions met" : "Entry conditions not met",
                Map.of(
                        "spotPrice",
                        snapshot.getSpotPrice().toString(),
                        "atmIV",
                        snapshot.getAtmIV() != null ? snapshot.getAtmIV().toString() : "N/A",
                        "decision",
                        enter));

        if (enter) {
            executeEntry(snapshot);
        }
    }

    /**
     * Executes entry orders. Builds orders from subclass, then executes via
     * JournaledMultiLegExecutor. On success, transitions to ACTIVE.
     */
    private void executeEntry(MarketSnapshot snapshot) {
        List<OrderRequest> orders = buildEntryOrders(snapshot);

        if (orders.isEmpty()) {
            logDecision("ENTRY_SKIP", "No entry orders built", Map.of());
            return;
        }

        // Set strategyId on all orders
        for (OrderRequest order : orders) {
            order.setStrategyId(id);
        }

        logDecision("ENTRY_EXEC", "Executing entry orders", Map.of("legs", orders.size()));

        // Execute entry orders via WAL-journaled parallel executor
        JournaledMultiLegExecutor.MultiLegResult result =
                journaledMultiLegExecutor.executeParallel(orders, id, "ENTRY", OrderPriority.STRATEGY_ENTRY);

        if (!result.isSuccess()) {
            logDecision(
                    "ENTRY_FAILED",
                    "Entry order execution failed, staying ARMED",
                    Map.of("groupId", result.getGroupId()));
            return;
        }

        long stamp = stampedLock.writeLock();
        try {
            this.status = StrategyStatus.ACTIVE;
            this.entryTime = LocalDateTime.now();
        } finally {
            stampedLock.unlockWrite(stamp);
        }
    }

    // ========================
    // EXIT
    // ========================

    private void evaluateExit(MarketSnapshot snapshot) {
        // Absolute PnL exit check runs at base level (applies to all strategy types)
        boolean exit = shouldExitByAbsolutePnl() || shouldExit(snapshot);
        BigDecimal currentPnl = calculateTotalPnl();

        logDecision(
                "EXIT_EVAL",
                exit ? "Exit triggered" : "No exit",
                Map.of(
                        "currentPnl",
                        currentPnl.toString(),
                        "entryPremium",
                        entryPremium != null ? entryPremium.toString() : "N/A",
                        "decision",
                        exit));

        if (exit) {
            initiateClose();
        }
    }

    // ========================
    // ADJUSTMENTS
    // ========================

    private void evaluateAdjustments(MarketSnapshot snapshot) {
        if (isAdjustmentInCooldown()) {
            return;
        }
        adjust(snapshot);
    }

    /**
     * Records that an adjustment was made, starting the cooldown timer.
     * Subclasses call this after successfully executing an adjustment.
     */
    protected void recordAdjustment(String adjustmentType) {
        this.lastAdjustmentTime = LocalDateTime.now();
        logDecision("ADJUST_DONE", "Adjustment recorded: " + adjustmentType, Map.of("type", adjustmentType));
    }

    // ========================
    // POSITION TRACKING
    // ========================

    @Override
    public List<Position> getPositions() {
        return List.copyOf(positions);
    }

    @Override
    public void updatePosition(Position updatedPosition) {
        long stamp = stampedLock.writeLock();
        try {
            for (int i = 0; i < positions.size(); i++) {
                if (positions.get(i).getId().equals(updatedPosition.getId())) {
                    positions.set(i, updatedPosition);
                    return;
                }
            }
        } finally {
            stampedLock.unlockWrite(stamp);
        }
    }

    @Override
    public void addPosition(Position position) {
        long stamp = stampedLock.writeLock();
        try {
            positions.add(position);
        } finally {
            stampedLock.unlockWrite(stamp);
        }
    }

    @Override
    public void removePosition(String positionId) {
        long stamp = stampedLock.writeLock();
        try {
            positions.removeIf(p -> p.getId().equals(positionId));
        } finally {
            stampedLock.unlockWrite(stamp);
        }
    }

    // ========================
    // P&L CALCULATIONS
    // ========================

    @Override
    public BigDecimal calculateTotalPnl() {
        return positions.stream()
                .map(p -> {
                    BigDecimal unrealized = p.getUnrealizedPnl();
                    BigDecimal realized = p.getRealizedPnl();
                    BigDecimal total = BigDecimal.ZERO;
                    if (unrealized != null) total = total.add(unrealized);
                    if (realized != null) total = total.add(realized);
                    return total;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Calculates the net position delta (sum of delta * quantity across all positions).
     * Positive delta = net long, negative = net short.
     */
    protected BigDecimal calculatePositionDelta() {
        return positions.stream()
                .filter(p -> p.getGreeks() != null && p.getGreeks().isAvailable())
                .map(p -> p.getGreeks().getDelta().multiply(BigDecimal.valueOf(p.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ========================
    // EXIT CONDITION HELPERS (used by subclasses in shouldExit)
    // ========================

    /**
     * Returns true if P&L has reached the target profit threshold.
     * Target = entryPremium * targetPercent.
     */
    protected boolean isTargetReached(BigDecimal targetPercent) {
        if (entryPremium == null || targetPercent == null) return false;
        BigDecimal target = entryPremium.multiply(targetPercent);
        return calculateTotalPnl().compareTo(target) >= 0;
    }

    /**
     * Returns true if P&L loss has exceeded the stop-loss threshold.
     * Stop-loss = entryPremium * stopLossMultiplier (negated since it's a loss).
     */
    protected boolean isStopLossHit(BigDecimal stopLossMultiplier) {
        if (entryPremium == null || stopLossMultiplier == null) return false;
        BigDecimal stopLoss = entryPremium.multiply(stopLossMultiplier).negate();
        return calculateTotalPnl().compareTo(stopLoss) <= 0;
    }

    /**
     * Returns true if total PnL has hit the absolute profit target or stop-loss.
     * Evaluates config.targetPnl and config.stopLossPnl against calculateTotalPnl().
     * Both are optional (null = disabled). Unlike the %-based helpers, these work
     * without entryPremium, making them suitable for adopted strategies.
     */
    protected boolean shouldExitByAbsolutePnl() {
        BigDecimal currentPnl = calculateTotalPnl();

        BigDecimal target = config.getTargetPnl();
        if (target != null && currentPnl.compareTo(target) >= 0) {
            logDecision(
                    "EXIT_CONDITION",
                    "Absolute profit target reached",
                    Map.of("currentPnl", currentPnl.toString(), "targetPnl", target.toString()));
            return true;
        }

        BigDecimal stopLoss = config.getStopLossPnl();
        if (stopLoss != null && currentPnl.compareTo(stopLoss) <= 0) {
            logDecision(
                    "EXIT_CONDITION",
                    "Absolute stop-loss hit",
                    Map.of("currentPnl", currentPnl.toString(), "stopLossPnl", stopLoss.toString()));
            return true;
        }

        return false;
    }

    /**
     * Returns true if the days to expiry is at or below the minimum threshold.
     * Used for time-based exits (don't hold into expiry).
     */
    protected boolean isDteExitTriggered(int minDaysToExpiry) {
        long dte = getDaysToExpiry();
        return dte <= minDaysToExpiry;
    }

    /** Returns days until the earliest position's expiry. MAX_VALUE if no positions. */
    protected long getDaysToExpiry() {
        // #TODO Phase 6.2 -- positions don't have expiry info directly; needs InstrumentService lookup
        // For now, return MAX_VALUE (no DTE exit)
        return Long.MAX_VALUE;
    }

    // ========================
    // POSITION HELPERS
    // ========================

    /** Finds the first short call position (quantity < 0, CE option). */
    protected Position getShortCall() {
        return positions.stream()
                .filter(p -> p.getQuantity() < 0
                        && p.getTradingSymbol() != null
                        && p.getTradingSymbol().endsWith("CE"))
                .findFirst()
                .orElse(null);
    }

    /** Finds the first short put position (quantity < 0, PE option). */
    protected Position getShortPut() {
        return positions.stream()
                .filter(p -> p.getQuantity() < 0
                        && p.getTradingSymbol() != null
                        && p.getTradingSymbol().endsWith("PE"))
                .findFirst()
                .orElse(null);
    }

    // ========================
    // TIMING & GUARDS
    // ========================

    /**
     * Checks if it's time for the next evaluation based on the monitoring interval.
     * Returns true if enough time has elapsed since last evaluation, or if this
     * is the first evaluation.
     */
    boolean shouldEvaluateNow() {
        if (lastEvaluationTime == null) return true;
        Duration sinceLastEval = Duration.between(lastEvaluationTime, LocalDateTime.now());
        return sinceLastEval.compareTo(getMonitoringInterval()) >= 0;
    }

    /**
     * Checks if any position has stale tick data (older than the stale data threshold).
     * For strategies with no positions (ARMED state), always returns false.
     */
    boolean isDataStale() {
        if (positions.isEmpty()) return false;
        Duration threshold = getStaleDataThreshold();
        LocalDateTime now = LocalDateTime.now();
        for (Position position : positions) {
            LocalDateTime lastUpdated = position.getLastUpdated();
            if (lastUpdated == null) return true;
            if (Duration.between(lastUpdated, now).compareTo(threshold) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if adjustment is in cooldown (too soon since last adjustment).
     */
    boolean isAdjustmentInCooldown() {
        if (lastAdjustmentTime == null) return false;
        Duration sinceLastAdjust = Duration.between(lastAdjustmentTime, LocalDateTime.now());
        return sinceLastAdjust.compareTo(getAdjustmentCooldown()) < 0;
    }

    /**
     * Checks if the strategy should auto-pause based on P&L or delta thresholds.
     * Returns true if either threshold is breached.
     */
    boolean shouldAutoPause() {
        BigDecimal pnlThreshold = getAutoPausePnlThreshold();
        if (pnlThreshold != null) {
            BigDecimal currentPnl = calculateTotalPnl();
            if (currentPnl.compareTo(pnlThreshold) <= 0) {
                logDecision(
                        "AUTO_PAUSE",
                        "P&L below auto-pause threshold",
                        Map.of("currentPnl", currentPnl.toString(), "threshold", pnlThreshold.toString()));
                return true;
            }
        }

        BigDecimal deltaThreshold = getAutoPauseDeltaThreshold();
        if (deltaThreshold != null) {
            BigDecimal currentDelta = calculatePositionDelta();
            if (currentDelta.abs().compareTo(deltaThreshold) > 0) {
                logDecision(
                        "AUTO_PAUSE",
                        "Delta exceeds auto-pause threshold",
                        Map.of("currentDelta", currentDelta.toString(), "threshold", deltaThreshold.toString()));
                return true;
            }
        }

        return false;
    }

    /**
     * Returns true if the current time is within the configured entry window.
     * Utility for subclass shouldEnter implementations.
     */
    protected boolean isWithinEntryWindow() {
        LocalTime now = LocalTime.now();
        LocalTime start = config.getEntryStartTime();
        LocalTime end = config.getEntryEndTime();
        if (start == null || end == null) return true;
        return !now.isBefore(start) && !now.isAfter(end);
    }

    /**
     * Rounds a price to the nearest strike interval.
     * Example: roundToStrike(22035, 50) = 22050.
     */
    protected BigDecimal roundToStrike(BigDecimal price) {
        BigDecimal interval = config.getStrikeInterval();
        if (interval == null || interval.compareTo(BigDecimal.ZERO) == 0) return price;
        return price.divide(interval, 0, java.math.RoundingMode.HALF_UP).multiply(interval);
    }

    // ========================
    // DECISION LOGGING
    // ========================

    /**
     * Logs a strategy decision via EventPublisherHelper. Every evaluation, skip,
     * and lifecycle change should be logged for post-trade analysis.
     */
    protected void logDecision(String category, String message, Map<String, Object> context) {
        if (eventPublisherHelper != null) {
            eventPublisherHelper.publishDecision(this, category, message, id, context);
        } else {
            // Services not yet injected (during construction/testing)
            log.info("[{}] {} - {} | {}", id, category, message, context);
        }
    }
}
