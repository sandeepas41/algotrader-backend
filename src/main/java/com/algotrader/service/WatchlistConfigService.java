package com.algotrader.service;

import com.algotrader.domain.model.WatchlistConfig;
import com.algotrader.entity.WatchlistConfigEntity;
import com.algotrader.mapper.WatchlistConfigMapper;
import com.algotrader.repository.jpa.WatchlistConfigJpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CRUD service for watchlist subscription configurations.
 *
 * <p>Manages pre-configured instrument subscriptions stored in H2. Each config
 * specifies an underlying, ATM ± strike count, and expiry type. Enabled configs
 * are used by WatchlistSubscriptionService on startup to auto-subscribe tokens.
 *
 * <p>Prevents duplicate underlyings — only one config per underlying is allowed.
 */
@Service
public class WatchlistConfigService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistConfigService.class);

    private final WatchlistConfigJpaRepository watchlistConfigJpaRepository;
    private final WatchlistConfigMapper watchlistConfigMapper;

    public WatchlistConfigService(
            WatchlistConfigJpaRepository watchlistConfigJpaRepository, WatchlistConfigMapper watchlistConfigMapper) {
        this.watchlistConfigJpaRepository = watchlistConfigJpaRepository;
        this.watchlistConfigMapper = watchlistConfigMapper;
    }

    /**
     * Returns all watchlist configs (both enabled and disabled).
     */
    public List<WatchlistConfig> getAll() {
        return watchlistConfigMapper.toDomainList(watchlistConfigJpaRepository.findAll());
    }

    /**
     * Returns only enabled configs. Used by WatchlistSubscriptionService on startup.
     */
    public List<WatchlistConfig> getEnabledConfigs() {
        return watchlistConfigMapper.toDomainList(watchlistConfigJpaRepository.findByEnabled(true));
    }

    /**
     * Creates a new watchlist config. Throws if underlying already exists.
     */
    public WatchlistConfig create(WatchlistConfig watchlistConfig) {
        if (watchlistConfigJpaRepository.existsByUnderlying(watchlistConfig.getUnderlying())) {
            throw new IllegalArgumentException(
                    "Watchlist config already exists for underlying: " + watchlistConfig.getUnderlying());
        }

        LocalDateTime now = LocalDateTime.now();
        watchlistConfig.setCreatedAt(now);
        watchlistConfig.setUpdatedAt(now);

        WatchlistConfigEntity entity = watchlistConfigMapper.toEntity(watchlistConfig);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        WatchlistConfigEntity saved = watchlistConfigJpaRepository.save(entity);
        log.info("Watchlist config created: id={}, underlying={}", saved.getId(), saved.getUnderlying());
        return watchlistConfigMapper.toDomain(saved);
    }

    /**
     * Updates an existing watchlist config by ID.
     */
    public WatchlistConfig update(Long id, WatchlistConfig updates) {
        WatchlistConfigEntity entity = watchlistConfigJpaRepository
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Watchlist config not found: " + id));

        WatchlistConfig existing = watchlistConfigMapper.toDomain(entity);

        if (updates.getUnderlying() != null) {
            existing.setUnderlying(updates.getUnderlying());
        }
        if (updates.getStrikesFromAtm() > 0) {
            existing.setStrikesFromAtm(updates.getStrikesFromAtm());
        }
        if (updates.getExpiryType() != null) {
            existing.setExpiryType(updates.getExpiryType());
        }

        existing.setUpdatedAt(LocalDateTime.now());

        WatchlistConfigEntity toSave = watchlistConfigMapper.toEntity(existing);
        toSave.setId(id);
        WatchlistConfigEntity saved = watchlistConfigJpaRepository.save(toSave);
        log.info("Watchlist config updated: id={}, underlying={}", saved.getId(), saved.getUnderlying());
        return watchlistConfigMapper.toDomain(saved);
    }

    /**
     * Toggles the enabled state of a watchlist config.
     */
    public WatchlistConfig toggle(Long id) {
        WatchlistConfigEntity entity = watchlistConfigJpaRepository
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Watchlist config not found: " + id));

        entity.setEnabled(!entity.isEnabled());
        entity.setUpdatedAt(LocalDateTime.now());

        WatchlistConfigEntity saved = watchlistConfigJpaRepository.save(entity);
        log.info("Watchlist config toggled: id={}, enabled={}", saved.getId(), saved.isEnabled());
        return watchlistConfigMapper.toDomain(saved);
    }

    /**
     * Deletes a watchlist config by ID.
     */
    public void delete(Long id) {
        if (!watchlistConfigJpaRepository.existsById(id)) {
            throw new IllegalArgumentException("Watchlist config not found: " + id);
        }
        watchlistConfigJpaRepository.deleteById(id);
        log.info("Watchlist config deleted: id={}", id);
    }
}
