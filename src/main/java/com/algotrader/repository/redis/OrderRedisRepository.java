package com.algotrader.repository.redis;

import com.algotrader.config.RedisConfig;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.model.Order;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis repository for active orders.
 *
 * <p>Active and pending orders are stored in Redis for sub-millisecond access by the
 * order management system. A separate "pending" set tracks orders that are OPEN or
 * TRIGGER_PENDING, enabling quick enumeration without scanning all orders.
 *
 * <p>A reverse index ({@code algo:order:broker-idx:{brokerOrderId} → internalId})
 * enables constant-time lookup by Kite's broker order ID, which is needed when
 * processing WebSocket order updates from the Kite ticker.
 *
 * <p>Completed/cancelled orders are eventually flushed to H2 by DataSyncService
 * and removed from Redis after the 24h TTL expires.
 */
@Repository
@RequiredArgsConstructor
public class OrderRedisRepository {

    private final RedisTemplate<String, Object> redisTemplate;

    public void save(Order order) {
        String key = RedisConfig.KEY_PREFIX_ORDER + order.getId();
        redisTemplate.opsForValue().set(key, order, RedisConfig.DEFAULT_TTL);

        // Track pending orders separately for quick enumeration
        if (order.getStatus() == OrderStatus.OPEN || order.getStatus() == OrderStatus.TRIGGER_PENDING) {
            redisTemplate.opsForSet().add(RedisConfig.KEY_SET_ORDERS_PENDING, order.getId());
        } else {
            redisTemplate.opsForSet().remove(RedisConfig.KEY_SET_ORDERS_PENDING, order.getId());
        }

        // Maintain reverse index: brokerOrderId → internal UUID for WebSocket update lookup
        if (order.getBrokerOrderId() != null) {
            String reverseKey = RedisConfig.KEY_BROKER_ORDER_INDEX + order.getBrokerOrderId();
            redisTemplate.opsForValue().set(reverseKey, order.getId(), RedisConfig.DEFAULT_TTL);
        }
    }

    public Optional<Order> findById(String id) {
        Object value = redisTemplate.opsForValue().get(RedisConfig.KEY_PREFIX_ORDER + id);
        return Optional.ofNullable((Order) value);
    }

    /**
     * Looks up an order by Kite's broker order ID using the reverse index.
     * Used by {@link com.algotrader.broker.KiteOrderUpdateHandler} to find orders
     * when processing WebSocket order status updates from Kite.
     */
    public Optional<Order> findByBrokerOrderId(String brokerOrderId) {
        String reverseKey = RedisConfig.KEY_BROKER_ORDER_INDEX + brokerOrderId;
        Object internalId = redisTemplate.opsForValue().get(reverseKey);
        if (internalId == null) {
            return Optional.empty();
        }
        return findById((String) internalId);
    }

    public List<Order> findPending() {
        Set<Object> ids = redisTemplate.opsForSet().members(RedisConfig.KEY_SET_ORDERS_PENDING);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        // Batch fetch all pending orders in a single Redis round-trip (avoids N+1)
        List<String> keys =
                ids.stream().map(id -> RedisConfig.KEY_PREFIX_ORDER + id).toList();
        List<Object> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) {
            return Collections.emptyList();
        }

        return values.stream().filter(Objects::nonNull).map(v -> (Order) v).toList();
    }

    public int countPending() {
        Long count = redisTemplate.opsForSet().size(RedisConfig.KEY_SET_ORDERS_PENDING);
        return count != null ? count.intValue() : 0;
    }

    public void delete(String id) {
        // Fetch order to get brokerOrderId before deleting, so we can clean up the reverse index
        Optional<Order> orderOpt = findById(id);

        redisTemplate.delete(RedisConfig.KEY_PREFIX_ORDER + id);
        redisTemplate.opsForSet().remove(RedisConfig.KEY_SET_ORDERS_PENDING, id);

        orderOpt.ifPresent(order -> {
            if (order.getBrokerOrderId() != null) {
                redisTemplate.delete(RedisConfig.KEY_BROKER_ORDER_INDEX + order.getBrokerOrderId());
            }
        });
    }
}
