package com.algotrader.oms;

import com.algotrader.event.OrderEvent;
import com.algotrader.event.OrderEventType;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Tracks in-flight order fills asynchronously using Spring's event system.
 *
 * <p>Used by {@link JournaledMultiLegExecutor#executeBuyFirstThenSell} to wait for
 * BUY leg fills before proceeding with SELL legs. Orders within a phase share a
 * {@code correlationId} (the journal's executionGroupId), enabling grouped tracking.
 *
 * <p><b>Usage pattern:</b>
 * <ol>
 *   <li>Call {@link #awaitFills} BEFORE routing orders (avoids race condition
 *       where a fill arrives before the await is registered)</li>
 *   <li>Route orders through the OrderRouter (they carry the correlationId)</li>
 *   <li>Block on the returned CompletableFuture (or use thenAccept for async)</li>
 *   <li>Future completes when all expected fills arrive, or completes exceptionally
 *       on rejection/timeout</li>
 * </ol>
 *
 * <p><b>Thread safety:</b> All state is in ConcurrentHashMap + AtomicInteger.
 * The EventListener callback may fire on any thread (event executor, WebSocket thread).
 */
@Service
public class OrderFillTracker {

    private static final Logger log = LoggerFactory.getLogger(OrderFillTracker.class);

    /** Default auto-expire duration for stale awaits that were never completed. */
    private static final Duration AUTO_EXPIRE = Duration.ofMinutes(2);

    private final ConcurrentHashMap<String, FillAwait> pendingAwaits = new ConcurrentHashMap<>();

    /** Scheduler for auto-expiring stale awaits. Single thread is sufficient. */
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "fill-tracker-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Registers a fill await for the given correlationId. Must be called BEFORE
     * routing orders to avoid the race where a fill arrives before the await exists.
     *
     * @param correlationId shared correlationId for all orders in this group
     * @param expectedCount number of fills expected before the future completes
     * @return a CompletableFuture that completes when all fills arrive, or
     *         completes exceptionally on rejection/timeout
     */
    public CompletableFuture<Void> awaitFills(String correlationId, int expectedCount) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        FillAwait await = new FillAwait(future, new AtomicInteger(expectedCount));
        pendingAwaits.put(correlationId, await);

        // Schedule auto-expire to prevent memory leaks from forgotten awaits
        cleanupScheduler.schedule(
                () -> {
                    FillAwait removed = pendingAwaits.remove(correlationId);
                    if (removed != null && !removed.future().isDone()) {
                        removed.future()
                                .completeExceptionally(new OrderFillTimeoutException(
                                        "Fill await expired for correlationId: " + correlationId + " (remaining: "
                                                + removed.remaining().get() + ")"));
                        log.warn(
                                "Fill await auto-expired: correlationId={}, remaining={}",
                                correlationId,
                                removed.remaining().get());
                    }
                },
                AUTO_EXPIRE.toMillis(),
                TimeUnit.MILLISECONDS);

        log.debug("Registered fill await: correlationId={}, expectedCount={}", correlationId, expectedCount);
        return future;
    }

    /**
     * Cancels a pending fill await. Used when order routing fails and we no longer
     * expect fills for this correlationId.
     */
    public void cancelAwait(String correlationId) {
        FillAwait removed = pendingAwaits.remove(correlationId);
        if (removed != null && !removed.future().isDone()) {
            removed.future().cancel(false);
            log.debug("Cancelled fill await: correlationId={}", correlationId);
        }
    }

    /**
     * Listens for order fill and rejection events. Decrements the fill counter
     * for the matching correlationId and completes the future when all fills arrive.
     * On rejection, completes exceptionally for fast-fail.
     */
    @EventListener
    public void onOrderEvent(OrderEvent event) {
        if (event.getOrder() == null || event.getOrder().getCorrelationId() == null) {
            return;
        }

        String correlationId = event.getOrder().getCorrelationId();
        FillAwait await = pendingAwaits.get(correlationId);
        if (await == null) {
            return;
        }

        if (event.getEventType() == OrderEventType.FILLED) {
            int remaining = await.remaining().decrementAndGet();
            log.debug(
                    "Fill received: correlationId={}, symbol={}, remaining={}",
                    correlationId,
                    event.getOrder().getTradingSymbol(),
                    remaining);

            if (remaining <= 0) {
                pendingAwaits.remove(correlationId);
                await.future().complete(null);
                log.info("All fills received: correlationId={}", correlationId);
            }
        } else if (event.getEventType() == OrderEventType.REJECTED) {
            pendingAwaits.remove(correlationId);
            await.future()
                    .completeExceptionally(
                            new OrderRejectedException("Order rejected: correlationId=" + correlationId + ", symbol="
                                    + event.getOrder().getTradingSymbol() + ", reason="
                                    + event.getOrder().getRejectionReason()));
            log.warn(
                    "Order rejected, failing fill await: correlationId={}, symbol={}, reason={}",
                    correlationId,
                    event.getOrder().getTradingSymbol(),
                    event.getOrder().getRejectionReason());
        }
    }

    /** Returns the number of currently pending fill awaits (for diagnostics). */
    public int getPendingCount() {
        return pendingAwaits.size();
    }

    /** Internal state for a pending fill await. */
    private record FillAwait(CompletableFuture<Void> future, AtomicInteger remaining) {}

    /** Thrown when BUY fills don't arrive within the configured timeout. */
    public static class OrderFillTimeoutException extends RuntimeException {
        public OrderFillTimeoutException(String message) {
            super(message);
        }
    }

    /** Thrown when an order in the tracked group is rejected by the broker. */
    public static class OrderRejectedException extends RuntimeException {
        public OrderRejectedException(String message) {
            super(message);
        }
    }
}
