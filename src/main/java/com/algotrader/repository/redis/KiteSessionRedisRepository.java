package com.algotrader.repository.redis;

import com.algotrader.config.RedisConfig;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis repository for Kite Connect session tokens.
 *
 * <p>Stores access tokens with a TTL aligned to Kite's 6 AM IST token expiry.
 * This is the primary session store — KiteSessionJpaRepository in H2 serves as
 * a fallback for recovery after Redis data loss.
 *
 * <p>Key format: algo:kite:session:{userId}
 * Value: JSON map with accessToken, userName, createdAt.
 */
@Repository
@RequiredArgsConstructor
public class KiteSessionRedisRepository {

    private static final Logger log = LoggerFactory.getLogger(KiteSessionRedisRepository.class);

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Store a Kite session with TTL aligned to 6 AM next day.
     * Kite tokens expire at 6:00 AM IST daily, so TTL = seconds until next 6 AM.
     */
    public void storeSession(String userId, String accessToken, String userName) {
        String key = RedisConfig.KEY_PREFIX_SESSION + userId;

        Map<String, Object> sessionData = Map.of(
                "accessToken", accessToken,
                "userName", userName,
                "createdAt", LocalDateTime.now().toString());

        // TTL aligned to Kite token expiry at 6 AM IST.
        // If called before 6 AM, token expires at 6 AM today; otherwise at 6 AM tomorrow.
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todaySixAm = LocalDate.now().atTime(LocalTime.of(6, 0));
        LocalDateTime nextExpiry = now.isBefore(todaySixAm) ? todaySixAm : todaySixAm.plusDays(1);
        Duration ttl = Duration.between(now, nextExpiry);

        redisTemplate.opsForValue().set(key, sessionData, ttl);
        log.info("Session stored for user {} with TTL {}s", userId, ttl.getSeconds());
    }

    /** Retrieve the stored access token for a user. */
    @SuppressWarnings("unchecked")
    public Optional<String> getAccessToken(String userId) {
        String key = RedisConfig.KEY_PREFIX_SESSION + userId;
        Object value = redisTemplate.opsForValue().get(key);
        if (value instanceof Map<?, ?> sessionData) {
            return Optional.ofNullable((String) sessionData.get("accessToken"));
        }
        return Optional.empty();
    }

    /** Check if a session exists for the given user. */
    public boolean hasSession(String userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisConfig.KEY_PREFIX_SESSION + userId));
    }

    /** Remove a session (e.g., on logout or token invalidation). */
    public void deleteSession(String userId) {
        redisTemplate.delete(RedisConfig.KEY_PREFIX_SESSION + userId);
    }
}
