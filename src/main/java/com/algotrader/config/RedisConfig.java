package com.algotrader.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration with Jackson 3 JSON serialization.
 *
 * <p>Uses {@link GenericJacksonJsonRedisSerializer} (Jackson 3 / tools.jackson) which has
 * built-in JSR-310 support for {@code LocalDateTime}, {@code LocalDate}, etc.
 * The older Jackson 2 variant ({@code GenericJackson2JsonRedisSerializer}) lacks JSR-310
 * support unless {@code jackson-datatype-jsr310} is explicitly added.
 *
 * <p>All keys are prefixed with "algo:" because this is a shared Redis server.
 * Key constants defined here are used by all Redis repositories to ensure consistent
 * key naming and avoid collisions with other applications.
 *
 * <p>Key schema:
 * <pre>
 *   algo:position:{id}        → Position JSON
 *   algo:positions:all         → Set of position IDs
 *   algo:order:{id}           → Order JSON
 *   algo:orders:pending        → Set of pending order IDs
 *   algo:order:broker-idx:{brokerOrderId} → Internal order ID (reverse lookup)
 *   algo:kite:session:{userId} → Session JSON (TTL aligned to 6 AM IST)
 *   algo:instrument:{token}    → Instrument JSON
 *   algo:daily:pnl:{date}      → Daily P&L JSON
 * </pre>
 */
@Configuration
public class RedisConfig {

    /** Global prefix for all keys — shared Redis server isolation. */
    public static final String KEY_PREFIX = "algo:";

    public static final String KEY_PREFIX_POSITION = KEY_PREFIX + "position:";
    public static final String KEY_PREFIX_ORDER = KEY_PREFIX + "order:";
    public static final String KEY_PREFIX_SESSION = KEY_PREFIX + "kite:session:";
    public static final String KEY_PREFIX_DAILY_PNL = KEY_PREFIX + "daily:pnl:";
    public static final String KEY_PREFIX_INSTRUMENT = KEY_PREFIX + "instrument:";

    public static final String KEY_SET_POSITIONS_ALL = KEY_PREFIX + "positions:all";
    public static final String KEY_SET_ORDERS_PENDING = KEY_PREFIX + "orders:pending";

    /** Reverse lookup: brokerOrderId → internal order UUID. */
    public static final String KEY_BROKER_ORDER_INDEX = KEY_PREFIX + "order:broker-idx:";

    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);

        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
        GenericJacksonJsonRedisSerializer jsonRedisSerializer =
                GenericJacksonJsonRedisSerializer.builder().build();

        redisTemplate.setKeySerializer(stringRedisSerializer);
        redisTemplate.setValueSerializer(jsonRedisSerializer);
        redisTemplate.setHashKeySerializer(stringRedisSerializer);
        redisTemplate.setHashValueSerializer(jsonRedisSerializer);

        return redisTemplate;
    }
}
