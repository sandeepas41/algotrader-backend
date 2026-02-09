package com.algotrader.observability;

import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.event.OrderEvent;
import com.algotrader.event.OrderEventType;
import com.algotrader.event.ReconciliationEvent;
import com.algotrader.event.TickEvent;
import com.algotrader.risk.AccountRiskChecker;
import com.algotrader.session.SessionHealthService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Registers and updates custom Micrometer metrics for the AlgoTrader platform.
 *
 * <p>Metrics are published to CloudWatch via micrometer-registry-cloudwatch2.
 * The first 10 custom metrics in CloudWatch are free. We define 7:
 * <ul>
 *   <li><b>orders.placed.count</b> (counter): Incremented on every PLACED OrderEvent</li>
 *   <li><b>orders.failed.count</b> (counter): Incremented on every REJECTED OrderEvent</li>
 *   <li><b>daily.pnl</b> (gauge): Current daily realized P&L from AccountRiskChecker</li>
 *   <li><b>active.strategies</b> (gauge): Number of active strategies from StrategyEngine</li>
 *   <li><b>kite.session.state</b> (gauge 0/1): Whether Kite session is active</li>
 *   <li><b>tick.latency</b> (timer): End-to-end tick processing latency</li>
 *   <li><b>reconciliation.mismatches</b> (counter): Number of position mismatches found</li>
 * </ul>
 *
 * <p>Gauges are lazily evaluated: Micrometer polls the supplier function when
 * scraping, so no @Scheduled polling is needed for gauge values. Counters and
 * timers are incremented via Spring ApplicationEvent listeners.
 */
@Service
public class CustomMetricsService {

    private static final Logger log = LoggerFactory.getLogger(CustomMetricsService.class);

    private final Counter ordersPlacedCounter;
    private final Counter ordersFailedCounter;
    private final Counter reconciliationMismatchCounter;
    private final Timer tickLatencyTimer;

    public CustomMetricsService(
            MeterRegistry meterRegistry,
            AccountRiskChecker accountRiskChecker,
            StrategyEngine strategyEngine,
            SessionHealthService sessionHealthService) {
        // Counters
        this.ordersPlacedCounter = Counter.builder("orders.placed.count")
                .description("Total orders successfully placed with broker")
                .register(meterRegistry);

        this.ordersFailedCounter = Counter.builder("orders.failed.count")
                .description("Total orders rejected by broker or risk checks")
                .register(meterRegistry);

        this.reconciliationMismatchCounter = Counter.builder("reconciliation.mismatches")
                .description("Total position mismatches found during reconciliation")
                .register(meterRegistry);

        // Timer (histogram)
        this.tickLatencyTimer = Timer.builder("tick.latency")
                .description("End-to-end tick processing latency from Kite WebSocket receipt")
                .publishPercentiles(0.5, 0.95, 0.99)
                .maximumExpectedValue(Duration.ofSeconds(1))
                .register(meterRegistry);

        // Gauges (lazily evaluated by Micrometer during scrape)
        meterRegistry.gauge("daily.pnl", accountRiskChecker, checker -> checker.getDailyRealisedPnl()
                .doubleValue());

        meterRegistry.gauge("active.strategies", strategyEngine, StrategyEngine::getActiveStrategyCount);

        meterRegistry.gauge(
                "kite.session.state", sessionHealthService, service -> service.isSessionActive() ? 1.0 : 0.0);
    }

    /**
     * Listens to OrderEvents to track orders placed and failed.
     * Runs at @Order(20) to avoid interfering with core processing listeners.
     */
    @EventListener
    @Order(20)
    public void onOrderEvent(OrderEvent event) {
        if (event.getEventType() == OrderEventType.PLACED) {
            ordersPlacedCounter.increment();
        } else if (event.getEventType() == OrderEventType.REJECTED) {
            ordersFailedCounter.increment();
        }
    }

    /**
     * Listens to TickEvents to record processing latency.
     * Runs at @Order(20) — after all core processing is complete — so the
     * recorded duration captures the full pipeline latency.
     */
    @EventListener
    @Order(20)
    public void onTickEvent(TickEvent event) {
        long elapsedNanos = System.nanoTime() - event.getReceivedAt();
        tickLatencyTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Listens to ReconciliationEvents to track mismatch counts.
     */
    @EventListener
    @Order(20)
    public void onReconciliationEvent(ReconciliationEvent event) {
        int mismatches = event.getResult().getMismatches() != null
                ? event.getResult().getMismatches().size()
                : 0;
        if (mismatches > 0) {
            reconciliationMismatchCounter.increment(mismatches);
            log.warn("Reconciliation found {} mismatches", mismatches);
        }
    }

    // Expose for testing
    Counter getOrdersPlacedCounter() {
        return ordersPlacedCounter;
    }

    Counter getOrdersFailedCounter() {
        return ordersFailedCounter;
    }

    Counter getReconciliationMismatchCounter() {
        return reconciliationMismatchCounter;
    }

    Timer getTickLatencyTimer() {
        return tickLatencyTimer;
    }
}
