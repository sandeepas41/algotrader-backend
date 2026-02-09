package com.algotrader.service;

import com.algotrader.domain.model.TradeNote;
import com.algotrader.entity.TradeNoteEntity;
import com.algotrader.mapper.TradeNoteMapper;
import com.algotrader.repository.jpa.TradeNoteJpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Trade journal service for creating and querying trade notes.
 *
 * <p>Trade notes are journal entries that traders attach to strategies or specific runs
 * to record observations, market conditions, and lessons learned. Tags enable filtering
 * and categorization. Star ratings (1-5) provide quick quality assessments.
 */
@Service
public class TradeNoteService {

    private static final Logger log = LoggerFactory.getLogger(TradeNoteService.class);

    private final TradeNoteJpaRepository tradeNoteJpaRepository;
    private final TradeNoteMapper tradeNoteMapper;

    public TradeNoteService(TradeNoteJpaRepository tradeNoteJpaRepository, TradeNoteMapper tradeNoteMapper) {
        this.tradeNoteJpaRepository = tradeNoteJpaRepository;
        this.tradeNoteMapper = tradeNoteMapper;
    }

    /**
     * Creates a new trade journal note.
     */
    public TradeNote createNote(TradeNote tradeNote) {
        tradeNote.setCreatedAt(LocalDateTime.now());
        TradeNoteEntity entity = tradeNoteMapper.toEntity(tradeNote);
        entity.setCreatedAt(LocalDateTime.now());
        TradeNoteEntity saved = tradeNoteJpaRepository.save(entity);
        log.info(
                "Trade note created: id={}, strategyId={}, runId={}",
                saved.getId(),
                saved.getStrategyId(),
                saved.getRunId());
        return tradeNoteMapper.toDomain(saved);
    }

    /**
     * Returns all notes for a strategy, ordered by creation time descending.
     */
    public List<TradeNote> getNotesByStrategy(String strategyId) {
        return tradeNoteMapper.toDomainList(tradeNoteJpaRepository.findByStrategyId(strategyId));
    }

    /**
     * Returns all notes for a specific strategy run.
     */
    public List<TradeNote> getNotesByRun(String runId) {
        return tradeNoteMapper.toDomainList(tradeNoteJpaRepository.findByRunId(runId));
    }

    /**
     * Returns all notes containing the given tag.
     */
    public List<TradeNote> getNotesByTag(String tag) {
        return tradeNoteMapper.toDomainList(tradeNoteJpaRepository.findByTagsContaining(tag));
    }

    /**
     * Deletes a trade note by ID.
     */
    public void deleteNote(Long id) {
        tradeNoteJpaRepository.deleteById(id);
        log.info("Trade note deleted: id={}", id);
    }

    /**
     * Updates an existing trade note.
     */
    public TradeNote updateNote(Long id, TradeNote updates) {
        TradeNoteEntity entity = tradeNoteJpaRepository
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Trade note not found: " + id));

        if (updates.getNote() != null) {
            entity.setNote(updates.getNote());
        }
        if (updates.getTags() != null) {
            entity.setTags(updates.getTags());
        }
        if (updates.getStarRating() != null) {
            entity.setStarRating(updates.getStarRating());
        }
        if (updates.getMarketCondition() != null) {
            entity.setMarketCondition(updates.getMarketCondition());
        }

        TradeNoteEntity saved = tradeNoteJpaRepository.save(entity);
        return tradeNoteMapper.toDomain(saved);
    }
}
