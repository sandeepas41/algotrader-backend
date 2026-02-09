package com.algotrader.risk;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.Position;
import com.algotrader.event.RiskEvent;
import com.algotrader.event.RiskEventType;
import com.algotrader.event.RiskLevel;
import com.algotrader.oms.OrderRouter;
import com.algotrader.repository.redis.OrderRedisRepository;
import com.algotrader.repository.redis.PositionRedisRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Emergency kill switch for closing all positions and cancelling all orders.
 *
 * <p>Design principles:
 * <ul>
 *   <li><b>Parallel execution:</b> Uses CompletableFuture.allOf for concurrent
 *       cancel/close operations to minimize time-to-flat</li>
 *   <li><b>Bypass OMS:</b> Calls BrokerGateway.placeOrder() directly for kill switch
 *       orders, bypassing risk validation and queuing (dead orders can't be risk-checked)</li>
 *   <li><b>Retry:</b> Failed cancel/close retried up to 3 times with 100ms delay</li>
 *   <li><b>Best-effort:</b> Failures on individual orders don't abort the overall kill;
 *       all errors are collected and reported in KillSwitchResult</li>
 *   <li><b>Idempotent:</b> AtomicBoolean prevents double-activation</li>
 * </ul>
 *
 * <p><b>Execution order:</b>
 * <ol>
 *   <li>Pause all strategies (prevent new order generation)</li>
 *   <li>Activate kill switch flag on OrderRouter (reject new non-KILL_SWITCH orders)</li>
 *   <li>Cancel all pending orders (parallel)</li>
 *   <li>Close all open positions with MARKET orders (parallel, bypass OMS)</li>
 * </ol>
 *
 * <p>Also provides {@link #pauseAllStrategies()} as a separate action (freeze without
 * closing) for less drastic risk responses.
 */
@Service
public class KillSwitchService {

    private static final Logger log = LoggerFactory.getLogger(KillSwitchService.class);

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 100;

    private final StrategyEngine strategyEngine;
    private final OrderRouter orderRouter;
    private final BrokerGateway brokerGateway;
    private final PositionRedisRepository positionRedisRepository;
    private final OrderRedisRepository orderRedisRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    private final AtomicBoolean killSwitchActive = new AtomicBoolean(false);

    public KillSwitchService(
            StrategyEngine strategyEngine,
            OrderRouter orderRouter,
            BrokerGateway brokerGateway,
            PositionRedisRepository positionRedisRepository,
            OrderRedisRepository orderRedisRepository,
            ApplicationEventPublisher applicationEventPublisher) {
        this.strategyEngine = strategyEngine;
        this.orderRouter = orderRouter;
        this.brokerGateway = brokerGateway;
        this.positionRedisRepository = positionRedisRepository;
        this.orderRedisRepository = orderRedisRepository;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    // ========================
    // KILL SWITCH ACTIVATION
    // ========================

    /**
     * Activates the kill switch: pauses all strategies, cancels all pending orders,
     * and closes all open positions with market orders.
     *
     * <p>Idempotent: returns immediately if already active.
     * Thread-safe via AtomicBoolean.getAndSet().
     *
     * @param reason human-readable reason for kill switch activation
     * @return result with counts and any errors
     */
    public KillSwitchResult activate(String reason) {
        if (killSwitchActive.getAndSet(true)) {
            log.warn("Kill switch already active, ignoring duplicate activation");
            return KillSwitchResult.alreadyActive();
        }

        log.error("KILL SWITCH ACTIVATED: {}", reason);

        applicationEventPublisher.publishEvent(new RiskEvent(
                this, RiskEventType.KILL_SWITCH_TRIGGERED, RiskLevel.CRITICAL, "Kill switch activated: " + reason));

        List<String> errors = new ArrayList<>();

        // Step 1: Pause all strategies to prevent new order generation
        int strategiesPaused = doPauseAllStrategies();

        // Step 2: Activate kill switch on OrderRouter to reject non-KILL_SWITCH orders
        orderRouter.activateKillSwitch();

        // Step 3: Cancel all pending orders in parallel
        int ordersCancelled = cancelAllOrdersParallel(errors);

        // Step 4: Close all positions with market orders, bypassing OMS
        int positionsClosed = closeAllPositionsParallel(errors);

        boolean success = errors.isEmpty();
        if (!success) {
            log.error("Kill switch completed with {} errors: {}", errors.size(), errors);
        } else {
            log.info(
                    "Kill switch complete: {} strategies paused, {} orders cancelled, {} positions closed",
                    strategiesPaused,
                    ordersCancelled,
                    positionsClosed);
        }

        return KillSwitchResult.builder()
                .success(success)
                .strategiesPaused(strategiesPaused)
                .ordersCancelled(ordersCancelled)
                .positionsClosed(positionsClosed)
                .reason(reason)
                .errors(errors)
                .build();
    }

    /**
     * Deactivates the kill switch, allowing normal trading to resume.
     */
    public void deactivate() {
        killSwitchActive.set(false);
        orderRouter.deactivateKillSwitch();
        log.info("Kill switch deactivated -- normal trading resumed");
    }

    /**
     * Returns whether the kill switch is currently active.
     */
    public boolean isActive() {
        return killSwitchActive.get();
    }

    // ========================
    // PAUSE ALL (separate from kill switch)
    // ========================

    /**
     * Pauses all active strategies without closing positions.
     * This is a less drastic action than full kill switch activation.
     *
     * @return number of strategies paused
     */
    public int pauseAllStrategies() {
        int paused = doPauseAllStrategies();
        log.info("Pause All: {} strategies paused (positions remain open)", paused);
        return paused;
    }

    // ========================
    // PARALLEL ORDER CANCELLATION
    // ========================

    /**
     * Cancels all pending orders in parallel with retry.
     * Returns count of successfully cancelled orders.
     */
    private int cancelAllOrdersParallel(List<String> errors) {
        List<Order> pendingOrders = orderRedisRepository.findPending();
        if (pendingOrders.isEmpty()) {
            return 0;
        }

        AtomicInteger cancelled = new AtomicInteger(0);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Order order : pendingOrders) {
            if (order.getBrokerOrderId() == null) {
                continue;
            }
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    cancelWithRetry(order.getBrokerOrderId());
                    cancelled.incrementAndGet();
                } catch (Exception e) {
                    String error = "Failed to cancel order " + order.getBrokerOrderId() + ": " + e.getMessage();
                    log.error(error);
                    synchronized (errors) {
                        errors.add(error);
                    }
                }
            });
            futures.add(future);
        }

        // Wait for all cancellations to complete (max 30 seconds)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Timeout waiting for order cancellations", e);
            synchronized (errors) {
                errors.add("Timeout waiting for order cancellations: " + e.getMessage());
            }
        }

        return cancelled.get();
    }

    // ========================
    // PARALLEL POSITION CLOSING
    // ========================

    /**
     * Closes all open positions with market orders in parallel.
     * Bypasses OMS -- calls BrokerGateway.placeOrder() directly.
     */
    private int closeAllPositionsParallel(List<String> errors) {
        List<Position> openPositions = positionRedisRepository.findAll();
        if (openPositions.isEmpty()) {
            return 0;
        }

        AtomicInteger closed = new AtomicInteger(0);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Position position : openPositions) {
            // Skip positions with zero quantity (already closed)
            if (position.getQuantity() == 0) {
                continue;
            }

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    closePositionWithRetry(position);
                    closed.incrementAndGet();
                } catch (Exception e) {
                    String error = "Failed to close position " + position.getId() + " (" + position.getTradingSymbol()
                            + "): " + e.getMessage();
                    log.error(error);
                    synchronized (errors) {
                        errors.add(error);
                    }
                }
            });
            futures.add(future);
        }

        // Wait for all position closures to complete (max 30 seconds)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Timeout waiting for position closures", e);
            synchronized (errors) {
                errors.add("Timeout waiting for position closures: " + e.getMessage());
            }
        }

        return closed.get();
    }

    // ========================
    // RETRY HELPERS
    // ========================

    /**
     * Cancels an order with up to MAX_RETRIES retries and RETRY_DELAY_MS between attempts.
     */
    private void cancelWithRetry(String brokerOrderId) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                brokerGateway.cancelOrder(brokerOrderId);
                return;
            } catch (Exception e) {
                lastException = e;
                log.warn(
                        "Cancel attempt {}/{} failed for order {}: {}",
                        attempt,
                        MAX_RETRIES,
                        brokerOrderId,
                        e.getMessage());
                if (attempt < MAX_RETRIES) {
                    sleep(RETRY_DELAY_MS);
                }
            }
        }
        throw new RuntimeException(
                "Failed to cancel order " + brokerOrderId + " after " + MAX_RETRIES + " attempts", lastException);
    }

    /**
     * Closes a position with a market order, bypassing OMS.
     * Retries up to MAX_RETRIES times with RETRY_DELAY_MS between attempts.
     *
     * <p><b>OMS bypass:</b> Kill switch orders go directly to BrokerGateway.placeOrder(),
     * skipping risk validation, idempotency, and queue -- because during emergency exit
     * we need immediate execution without any checks that might reject the order.
     */
    private void closePositionWithRetry(Position position) {
        // Build the counter-order: buy to close short, sell to close long
        Order closeOrder = Order.builder()
                .instrumentToken(position.getInstrumentToken())
                .tradingSymbol(position.getTradingSymbol())
                .exchange(position.getExchange())
                .side(position.getQuantity() > 0 ? OrderSide.SELL : OrderSide.BUY)
                .type(OrderType.MARKET)
                .product("NRML")
                .quantity(Math.abs(position.getQuantity()))
                .build();

        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                brokerGateway.placeOrder(closeOrder);
                log.info(
                        "Kill switch: closed position {} ({} x {})",
                        position.getId(),
                        position.getTradingSymbol(),
                        position.getQuantity());
                return;
            } catch (Exception e) {
                lastException = e;
                log.warn(
                        "Close attempt {}/{} failed for position {} ({}): {}",
                        attempt,
                        MAX_RETRIES,
                        position.getId(),
                        position.getTradingSymbol(),
                        e.getMessage());
                if (attempt < MAX_RETRIES) {
                    sleep(RETRY_DELAY_MS);
                }
            }
        }
        throw new RuntimeException(
                "Failed to close position " + position.getId() + " after " + MAX_RETRIES + " attempts", lastException);
    }

    // ========================
    // INTERNALS
    // ========================

    private int doPauseAllStrategies() {
        try {
            strategyEngine.pauseAll();
            int count = strategyEngine.getActiveStrategies().size();
            log.info("Kill switch: paused {} strategies", count);
            return count;
        } catch (Exception e) {
            log.error("Failed to pause strategies: {}", e.getMessage());
            return 0;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
