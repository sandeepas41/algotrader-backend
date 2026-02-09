package com.algotrader.oms;

import com.algotrader.domain.enums.OrderPriority;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Generates structured order tags for Kite's order tagging system.
 *
 * <p>Tag format: {@code {strategyPrefix_3}{actionCode_3}{seq_4}} (max 10 chars, Kite limit is 20).
 * Example: "IC1ENT0001" = IronCondor strategy #1, Entry action, sequence 0001.
 *
 * <p>Design decisions:
 * <ul>
 *   <li>Tags are 10 chars (well within Kite's 20 char limit) to leave room
 *       for Kite's own prefix if needed</li>
 *   <li>4-digit sequence (0000-9999) limits to 10,000 orders per strategy per day.
 *       This is more than sufficient — a hyperactive scalping strategy doing 100
 *       trades/day would only use 1% of the sequence space</li>
 *   <li>Daily reset clears all counters at midnight to avoid sequence exhaustion
 *       across days. The date is tracked via {@link #lastResetDate}</li>
 *   <li>Sequence wraps at 10000 using modulo rather than throwing an error,
 *       so if the limit is ever hit, tags will repeat but orders won't fail</li>
 * </ul>
 *
 * <p>These tags survive in Kite's order book and are used for end-of-day
 * reconciliation to match orders with strategies.
 */
@Component
public class OrderTagGenerator {

    private static final Logger log = LoggerFactory.getLogger(OrderTagGenerator.class);

    private static final int MAX_SEQUENCE = 10000;

    /** Maps OrderPriority to 3-char action code for the tag. */
    static final Map<OrderPriority, String> ACTION_CODES = Map.of(
            OrderPriority.KILL_SWITCH, "KIL",
            OrderPriority.RISK_EXIT, "RSK",
            OrderPriority.STRATEGY_EXIT, "EXT",
            OrderPriority.STRATEGY_ADJUSTMENT, "ADJ",
            OrderPriority.STRATEGY_ENTRY, "ENT",
            OrderPriority.MANUAL, "MAN");

    /**
     * Per-(strategyPrefix+actionCode) sequence counters.
     * Key: e.g. "IC1ENT", value: atomic counter.
     */
    private final ConcurrentHashMap<String, AtomicInteger> sequenceCounters = new ConcurrentHashMap<>();

    /** Tracks the date of the last counter reset for daily rollover. */
    private final AtomicReference<LocalDate> lastResetDate = new AtomicReference<>(LocalDate.now());

    /**
     * Generates a tag for an order.
     *
     * <p>Example: generate("IronCondor1", STRATEGY_ENTRY) -> "IRO ENT0001"
     * (without space: "IROENT0001")
     *
     * @param strategyId the strategy ID (first 3 chars used as prefix, "GEN" for null)
     * @param priority   determines the action code portion of the tag
     * @return a 10-character order tag
     */
    public String generate(String strategyId, OrderPriority priority) {
        resetIfNewDay();

        String strategyPrefix = strategyPrefix(strategyId);
        String actionCode = ACTION_CODES.getOrDefault(priority, "GEN");

        String counterKey = strategyPrefix + actionCode;
        AtomicInteger counter = sequenceCounters.computeIfAbsent(counterKey, k -> new AtomicInteger(0));
        int seq = counter.incrementAndGet();

        // Wrap at 10000 to avoid exceeding 4 digits
        String tag = String.format("%s%s%04d", strategyPrefix, actionCode, seq % MAX_SEQUENCE);
        log.debug("Generated order tag: {}", tag);
        return tag;
    }

    /**
     * Resets all counters. Called daily or explicitly for testing.
     */
    public void resetCounters() {
        sequenceCounters.clear();
        lastResetDate.set(LocalDate.now());
        log.info("Order tag counters reset");
    }

    /**
     * Returns the current sequence number for a given strategy+action combination.
     * Useful for monitoring tag usage.
     */
    public int getCurrentSequence(String strategyId, OrderPriority priority) {
        String counterKey = strategyPrefix(strategyId) + ACTION_CODES.getOrDefault(priority, "GEN");
        AtomicInteger counter = sequenceCounters.get(counterKey);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Resets counters if the date has changed since last reset.
     * This ensures sequences start fresh each trading day.
     */
    private void resetIfNewDay() {
        LocalDate today = LocalDate.now();
        LocalDate lastReset = lastResetDate.get();

        if (!today.equals(lastReset)) {
            if (lastResetDate.compareAndSet(lastReset, today)) {
                sequenceCounters.clear();
                log.info("Order tag counters reset for new day: {}", today);
            }
        }
    }

    private String strategyPrefix(String strategyId) {
        if (strategyId == null || strategyId.isEmpty()) {
            return "GEN";
        }
        return strategyId.substring(0, Math.min(3, strategyId.length())).toUpperCase();
    }
}
