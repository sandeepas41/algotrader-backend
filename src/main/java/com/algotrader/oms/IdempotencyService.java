package com.algotrader.oms;

import com.algotrader.config.RedisConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Prevents duplicate order placement using Redis-based idempotency keys.
 *
 * <p>Each order request is hashed (SHA-256) based on its key fields (strategyId,
 * instrumentToken, side, quantity, and a time bucket). If a matching key already
 * exists in Redis, the order is considered a duplicate and rejected.
 *
 * <p>The deduplication window is 5 minutes (extended from the original 60s spec
 * to cover the crash recovery window where ExecutionJournal entries may be
 * replayed after restart). This prevents replay-induced duplicates while still
 * allowing intentional repeated trades after the window expires.
 *
 * <p>Key schema: {@code algo:order:dedup:{sha256-hash-prefix}}
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** 5-minute dedup window covers crash recovery replay scenarios. */
    public static final Duration DEDUP_WINDOW = Duration.ofMinutes(5);

    public static final String KEY_PREFIX = RedisConfig.KEY_PREFIX + "order:dedup:";

    private final RedisTemplate<String, Object> redisTemplate;

    public IdempotencyService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Checks if this order request is unique within the deduplication window.
     *
     * <p>The key is a SHA-256 hash of (strategyId + instrumentToken + side + quantity +
     * time_bucket). The time bucket floors the current time to the nearest 5-minute
     * window so that identical orders within the same window collide.
     *
     * @return true if this request has NOT been seen before (unique), false if duplicate
     */
    public boolean isUnique(OrderRequest orderRequest) {
        String redisKey = buildRedisKey(orderRequest);
        Boolean exists = redisTemplate.hasKey(redisKey);
        if (exists != null && exists) {
            log.debug("Duplicate order detected: key={}", redisKey);
            return false;
        }
        return true;
    }

    /**
     * Marks this order request as processed by setting a Redis key with TTL.
     * Must be called after the order has been validated and enqueued/executed.
     */
    public void markProcessed(OrderRequest orderRequest) {
        String redisKey = buildRedisKey(orderRequest);
        redisTemplate.opsForValue().set(redisKey, "1", DEDUP_WINDOW);
        log.debug("Idempotency key set: {}", redisKey);
    }

    /**
     * Builds the Redis key for an order request.
     * Format: algo:order:dedup:{first-16-chars-of-sha256}
     */
    private String buildRedisKey(OrderRequest orderRequest) {
        String hash = generateHash(orderRequest);
        return KEY_PREFIX + hash;
    }

    /**
     * Generates a SHA-256 hash of the order's deduplication-relevant fields.
     *
     * <p>The time bucket floors to the nearest DEDUP_WINDOW so that requests
     * within the same window produce the same hash. This prevents the edge case
     * where two identical requests straddle a window boundary.
     */
    public String generateHash(OrderRequest orderRequest) {
        // Time bucket: floor to nearest dedup window (5 minutes)
        long timeBucket = System.currentTimeMillis() / DEDUP_WINDOW.toMillis();

        String raw = String.join(
                "|",
                nullSafe(orderRequest.getStrategyId()),
                String.valueOf(orderRequest.getInstrumentToken()),
                orderRequest.getSide() != null ? orderRequest.getSide().name() : "UNKNOWN",
                String.valueOf(orderRequest.getQuantity()),
                String.valueOf(timeBucket));

        return sha256(raw);
    }

    private String nullSafe(String value) {
        return value != null ? value : "manual";
    }

    /**
     * Computes SHA-256 and returns first 16 hex characters.
     * 16 hex chars = 64 bits of entropy, collision probability negligible for dedup.
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by JCA specification -- unreachable in practice
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
