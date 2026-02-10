package com.algotrader.repository.jpa;

import com.algotrader.entity.WatchlistConfigEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the watchlist_configs table.
 *
 * <p>Stores pre-configured instrument subscriptions that are auto-subscribed on
 * startup. The WatchlistSubscriptionService queries enabled configs to determine
 * which instruments to subscribe.
 */
@Repository
public interface WatchlistConfigJpaRepository extends JpaRepository<WatchlistConfigEntity, Long> {

    List<WatchlistConfigEntity> findByEnabled(boolean enabled);

    Optional<WatchlistConfigEntity> findByUnderlying(String underlying);

    boolean existsByUnderlying(String underlying);
}
