package com.algotrader.repository.redis;

import com.algotrader.config.RedisConfig;
import com.algotrader.domain.model.Position;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis repository for real-time position data.
 *
 * <p>Positions are the primary real-time data in the system — they are updated on every
 * tick and read by the dashboard, strategy engine, and risk manager. Redis provides
 * the sub-millisecond access needed for these hot-path reads.
 *
 * <p>H2 (via PositionJpaRepository) stores historical snapshots only. During market hours,
 * PositionService.getPositions() always reads from this Redis repository, never from H2.
 */
@Repository
@RequiredArgsConstructor
public class PositionRedisRepository {

    private final RedisTemplate<String, Object> redisTemplate;

    public void save(Position position) {
        String key = RedisConfig.KEY_PREFIX_POSITION + position.getId();
        redisTemplate.opsForValue().set(key, position, RedisConfig.DEFAULT_TTL);
        redisTemplate.opsForSet().add(RedisConfig.KEY_SET_POSITIONS_ALL, position.getId());
    }

    public Optional<Position> findById(String id) {
        String key = RedisConfig.KEY_PREFIX_POSITION + id;
        Object value = redisTemplate.opsForValue().get(key);
        return Optional.ofNullable((Position) value);
    }

    public List<Position> findAll() {
        Set<Object> ids = redisTemplate.opsForSet().members(RedisConfig.KEY_SET_POSITIONS_ALL);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> keys =
                ids.stream().map(id -> RedisConfig.KEY_PREFIX_POSITION + id).toList();

        List<Object> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) {
            return Collections.emptyList();
        }

        return values.stream().filter(Objects::nonNull).map(v -> (Position) v).toList();
    }

    public void delete(String id) {
        redisTemplate.delete(RedisConfig.KEY_PREFIX_POSITION + id);
        redisTemplate.opsForSet().remove(RedisConfig.KEY_SET_POSITIONS_ALL, id);
    }

    public void deleteAll() {
        Set<Object> ids = redisTemplate.opsForSet().members(RedisConfig.KEY_SET_POSITIONS_ALL);
        if (ids != null && !ids.isEmpty()) {
            List<String> keys =
                    ids.stream().map(id -> RedisConfig.KEY_PREFIX_POSITION + id).toList();
            redisTemplate.delete(keys);
        }
        redisTemplate.delete(RedisConfig.KEY_SET_POSITIONS_ALL);
    }
}
