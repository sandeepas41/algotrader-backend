package com.algotrader.risk;

import com.algotrader.domain.model.RiskLimitHistory;
import com.algotrader.entity.RiskLimitHistoryEntity;
import com.algotrader.mapper.RiskLimitHistoryMapper;
import com.algotrader.repository.jpa.RiskLimitHistoryJpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Persists risk limit changes to H2 for audit trail and crash recovery.
 *
 * <p>Every time a risk limit is modified, the old and new values are recorded
 * with a timestamp, reason, and who made the change. On startup, the latest
 * limits can be reloaded from the database to override YAML defaults.
 *
 * <p>The synchronous in-memory update contract: when a limit change is made,
 * the in-memory RiskLimits is updated FIRST, then the change is persisted to H2.
 * This ensures the API response reflects the new limits immediately.
 */
@Service
public class RiskLimitPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(RiskLimitPersistenceService.class);

    private final RiskLimitHistoryJpaRepository riskLimitHistoryJpaRepository;
    private final RiskLimitHistoryMapper riskLimitHistoryMapper;

    public RiskLimitPersistenceService(
            RiskLimitHistoryJpaRepository riskLimitHistoryJpaRepository,
            RiskLimitHistoryMapper riskLimitHistoryMapper) {
        this.riskLimitHistoryJpaRepository = riskLimitHistoryJpaRepository;
        this.riskLimitHistoryMapper = riskLimitHistoryMapper;
    }

    /**
     * Records a single limit change to the audit trail.
     *
     * @param limitType category of limit (e.g., "GLOBAL", "UNDERLYING:NIFTY")
     * @param limitName specific limit name (e.g., "dailyLossLimit")
     * @param oldValue  previous value (null if newly set)
     * @param newValue  new value
     * @param changedBy who made the change (user ID or "SYSTEM")
     * @param reason    optional reason for the change
     */
    public void recordChange(
            String limitType, String limitName, String oldValue, String newValue, String changedBy, String reason) {
        // Skip recording if value didn't actually change
        if (Objects.equals(oldValue, newValue)) {
            return;
        }

        RiskLimitHistoryEntity entity = RiskLimitHistoryEntity.builder()
                .limitType(limitType)
                .limitName(limitName)
                .oldValue(oldValue)
                .newValue(newValue)
                .changedBy(changedBy)
                .reason(reason)
                .timestamp(LocalDateTime.now())
                .build();

        riskLimitHistoryJpaRepository.save(entity);
        log.info(
                "Risk limit change recorded: {}.{} = {} -> {} (by {})",
                limitType,
                limitName,
                oldValue,
                newValue,
                changedBy);
    }

    /**
     * Returns the full change history for a specific limit type, newest first.
     */
    public List<RiskLimitHistory> getHistoryByType(String limitType) {
        List<RiskLimitHistoryEntity> entities =
                riskLimitHistoryJpaRepository.findByLimitTypeOrderByTimestampDesc(limitType);
        return riskLimitHistoryMapper.toDomainList(entities);
    }

    /**
     * Returns the full change history within a date range, newest first.
     */
    public List<RiskLimitHistory> getHistoryByDateRange(LocalDateTime from, LocalDateTime to) {
        List<RiskLimitHistoryEntity> entities = riskLimitHistoryJpaRepository.findByDateRange(from, to);
        return riskLimitHistoryMapper.toDomainList(entities);
    }

    /**
     * Returns all change history entries (for loading latest limits on startup).
     */
    public List<RiskLimitHistory> getAllHistory() {
        List<RiskLimitHistoryEntity> entities = riskLimitHistoryJpaRepository.findAll();
        return riskLimitHistoryMapper.toDomainList(entities);
    }
}
