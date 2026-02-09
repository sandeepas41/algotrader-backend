package com.algotrader.event;

import com.algotrader.domain.model.Tick;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a new market data tick is received from the Kite WebSocket.
 *
 * <p>This is the highest-frequency event in the system — one per instrument per tick
 * interval (~1 tick/sec for actively traded instruments). Listeners must be fast
 * to avoid backpressure on the WebSocket receiver thread.
 *
 * <p>Listener execution order is enforced via {@code @Order} annotations:
 * <ol>
 *   <li>TickProcessor (cache update) — @Order(1)</li>
 *   <li>IndicatorService (technical indicators) — @Order(2)</li>
 *   <li>PositionService (P&L update) — @Order(3)</li>
 *   <li>StrategyEngine (condition evaluation) — @Order(4)</li>
 *   <li>RiskManager (risk checks) — @Order(5)</li>
 *   <li>TickRelayHandler (WebSocket push to FE) — @Order(10), async</li>
 * </ol>
 *
 * <p>The {@code receivedAt} field captures the nanoTime when the tick was received
 * from Kite, enabling end-to-end latency measurement through the processing pipeline.
 */
public class TickEvent extends ApplicationEvent {

    private final Tick tick;
    private final long receivedAt;

    public TickEvent(Object source, Tick tick) {
        super(source);
        this.tick = tick;
        this.receivedAt = System.nanoTime();
    }

    public Tick getTick() {
        return tick;
    }

    /**
     * System.nanoTime() when this tick was received from the Kite WebSocket.
     * Use {@code System.nanoTime() - receivedAt} to measure processing latency.
     */
    public long getReceivedAt() {
        return receivedAt;
    }
}
