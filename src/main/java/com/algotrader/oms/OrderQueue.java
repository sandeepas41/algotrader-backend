package com.algotrader.oms;

import com.algotrader.domain.enums.OrderPriority;
import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Priority-based order queue that ensures critical orders are processed before routine ones.
 *
 * <p>Uses a {@link PriorityBlockingQueue} with two-level sorting:
 * <ol>
 *   <li>Priority level (lower = higher priority): KILL_SWITCH(0) before MANUAL(5)</li>
 *   <li>Sequence number (FIFO within same priority): earlier enqueue dequeues first</li>
 * </ol>
 *
 * <p>The queue is unbounded (no capacity limit) because order volume is low and
 * bounded by risk checks. If orders accumulate, that's a processing bottleneck
 * that should be detected via metrics, not dropped orders.
 *
 * <p>Consumer threads call {@link #take()} which blocks until an order is available,
 * eliminating the 10ms polling delay from a {@code @Scheduled(fixedDelay = 10)} approach.
 */
@Component
public class OrderQueue {

    private static final Logger log = LoggerFactory.getLogger(OrderQueue.class);

    private static final int INITIAL_CAPACITY = 64;

    /** Monotonically increasing counter for FIFO ordering within the same priority. */
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    private final PriorityBlockingQueue<PrioritizedOrder> queue = new PriorityBlockingQueue<>(
            INITIAL_CAPACITY,
            Comparator.<PrioritizedOrder>comparingInt(o -> o.getPriority().getLevel())
                    .thenComparingLong(PrioritizedOrder::getSequenceNumber));

    /**
     * Enqueues an order request with the given priority.
     *
     * <p>The sequence number is assigned atomically to preserve FIFO ordering
     * for orders at the same priority level. The enqueue timestamp enables
     * queue latency monitoring.
     */
    public void enqueue(OrderRequest orderRequest, OrderPriority priority) {
        PrioritizedOrder prioritizedOrder = PrioritizedOrder.builder()
                .orderRequest(orderRequest)
                .priority(priority)
                .sequenceNumber(sequenceCounter.incrementAndGet())
                .enqueuedAt(System.currentTimeMillis())
                .build();

        queue.put(prioritizedOrder);
        log.debug(
                "Order enqueued: priority={}, symbol={}, queueSize={}",
                priority,
                orderRequest.getTradingSymbol(),
                queue.size());
    }

    /**
     * Retrieves and removes the highest-priority order, blocking until one is available.
     *
     * <p>This is the primary consumer method. The calling thread blocks on an empty
     * queue and is woken when {@link #enqueue} adds a new order. This is more
     * efficient than polling: zero latency on arrival, zero CPU waste on idle.
     *
     * @return the highest-priority order
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public PrioritizedOrder take() throws InterruptedException {
        return queue.take();
    }

    /**
     * Retrieves and removes the highest-priority order, or returns null if the queue is empty.
     * Used for non-blocking checks (e.g., during shutdown drain).
     */
    public PrioritizedOrder poll() {
        return queue.poll();
    }

    /** Returns the current number of pending orders in the queue. */
    public int size() {
        return queue.size();
    }

    /** Returns true if no orders are pending. */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Removes all orders from the queue. Used during kill switch activation
     * to clear pending orders that are no longer relevant.
     */
    public void clear() {
        int cleared = queue.size();
        queue.clear();
        if (cleared > 0) {
            log.info("Order queue cleared: {} orders removed", cleared);
        }
    }
}
