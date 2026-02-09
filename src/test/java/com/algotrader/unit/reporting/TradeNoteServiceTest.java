package com.algotrader.unit.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.domain.model.TradeNote;
import com.algotrader.entity.TradeNoteEntity;
import com.algotrader.mapper.TradeNoteMapper;
import com.algotrader.repository.jpa.TradeNoteJpaRepository;
import com.algotrader.service.TradeNoteService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for TradeNoteService (Task 18.2).
 *
 * <p>Verifies: create note, query by strategy/run/tag, update, delete.
 */
class TradeNoteServiceTest {

    @Mock
    private TradeNoteJpaRepository tradeNoteJpaRepository;

    @Mock
    private TradeNoteMapper tradeNoteMapper;

    private TradeNoteService tradeNoteService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        tradeNoteService = new TradeNoteService(tradeNoteJpaRepository, tradeNoteMapper);
    }

    @Test
    void createNote_persistsAndReturns() {
        TradeNote input = TradeNote.builder()
                .strategyId("STR-1")
                .runId("RUN-1")
                .note("Good entry timing")
                .tags("timing,entry")
                .starRating(4)
                .marketCondition("TRENDING")
                .build();

        TradeNoteEntity entity = TradeNoteEntity.builder()
                .id(1L)
                .strategyId("STR-1")
                .runId("RUN-1")
                .note("Good entry timing")
                .tags("timing,entry")
                .starRating(4)
                .marketCondition("TRENDING")
                .createdAt(LocalDateTime.now())
                .build();

        when(tradeNoteMapper.toEntity(any())).thenReturn(entity);
        when(tradeNoteJpaRepository.save(entity)).thenReturn(entity);
        when(tradeNoteMapper.toDomain(entity)).thenReturn(input);

        TradeNote result = tradeNoteService.createNote(input);

        assertThat(result.getStrategyId()).isEqualTo("STR-1");
        verify(tradeNoteJpaRepository).save(any());
    }

    @Test
    void getNotesByStrategy_delegatesToRepo() {
        List<TradeNoteEntity> entities = List.of(
                TradeNoteEntity.builder()
                        .id(1L)
                        .strategyId("STR-1")
                        .note("Note 1")
                        .build(),
                TradeNoteEntity.builder()
                        .id(2L)
                        .strategyId("STR-1")
                        .note("Note 2")
                        .build());

        when(tradeNoteJpaRepository.findByStrategyId("STR-1")).thenReturn(entities);
        when(tradeNoteMapper.toDomainList(entities))
                .thenReturn(List.of(
                        TradeNote.builder().id(1L).note("Note 1").build(),
                        TradeNote.builder().id(2L).note("Note 2").build()));

        List<TradeNote> result = tradeNoteService.getNotesByStrategy("STR-1");

        assertThat(result).hasSize(2);
    }

    @Test
    void getNotesByRun_delegatesToRepo() {
        when(tradeNoteJpaRepository.findByRunId("RUN-1")).thenReturn(List.of());
        when(tradeNoteMapper.toDomainList(List.of())).thenReturn(List.of());

        List<TradeNote> result = tradeNoteService.getNotesByRun("RUN-1");

        assertThat(result).isEmpty();
    }

    @Test
    void getNotesByTag_delegatesToRepo() {
        when(tradeNoteJpaRepository.findByTagsContaining("delta")).thenReturn(List.of());
        when(tradeNoteMapper.toDomainList(List.of())).thenReturn(List.of());

        List<TradeNote> result = tradeNoteService.getNotesByTag("delta");

        assertThat(result).isEmpty();
    }

    @Test
    void deleteNote_deletesById() {
        tradeNoteService.deleteNote(1L);
        verify(tradeNoteJpaRepository).deleteById(1L);
    }

    @Test
    void updateNote_updatesFields() {
        TradeNoteEntity existing = TradeNoteEntity.builder()
                .id(1L)
                .strategyId("STR-1")
                .note("Old note")
                .tags("old")
                .starRating(2)
                .build();

        when(tradeNoteJpaRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(tradeNoteJpaRepository.save(existing)).thenReturn(existing);
        when(tradeNoteMapper.toDomain(existing))
                .thenReturn(TradeNote.builder().id(1L).note("Updated note").build());

        TradeNote updates = TradeNote.builder()
                .note("Updated note")
                .tags("new,updated")
                .starRating(5)
                .build();

        TradeNote result = tradeNoteService.updateNote(1L, updates);

        assertThat(existing.getNote()).isEqualTo("Updated note");
        assertThat(existing.getTags()).isEqualTo("new,updated");
        assertThat(existing.getStarRating()).isEqualTo(5);
    }

    @Test
    void updateNote_throwsIfNotFound() {
        when(tradeNoteJpaRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tradeNoteService.updateNote(
                        999L, TradeNote.builder().note("x").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Trade note not found");
    }
}
