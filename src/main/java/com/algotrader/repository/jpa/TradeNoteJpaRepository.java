package com.algotrader.repository.jpa;

import com.algotrader.entity.TradeNoteEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the trade_notes table.
 * Supports querying journal entries by strategy, run, and tags for the trade journal UI.
 */
@Repository
public interface TradeNoteJpaRepository extends JpaRepository<TradeNoteEntity, Long> {

    List<TradeNoteEntity> findByStrategyId(String strategyId);

    List<TradeNoteEntity> findByRunId(String runId);

    List<TradeNoteEntity> findByStrategyIdAndRunId(String strategyId, String runId);

    List<TradeNoteEntity> findByTagsContaining(String tag);
}
