package com.algotrader.unit.reporting;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.TradeNoteController;
import com.algotrader.domain.model.TradeNote;
import com.algotrader.exception.GlobalExceptionHandler;
import com.algotrader.service.TradeNoteService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for TradeNoteController (Task 18.2).
 *
 * <p>Verifies: create note (201), query by strategyId/runId/tag,
 * update, delete, and validation error handling.
 */
class TradeNoteControllerTest {

    private MockMvc mockMvc;

    @Mock
    private TradeNoteService tradeNoteService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TradeNoteController controller = new TradeNoteController(tradeNoteService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createNote_returns201() throws Exception {
        TradeNote created = TradeNote.builder()
                .id(1L)
                .strategyId("STR-1")
                .runId("RUN-1")
                .note("Good entry timing")
                .tags("timing,entry")
                .starRating(4)
                .marketCondition("TRENDING")
                .build();

        when(tradeNoteService.createNote(any())).thenReturn(created);

        mockMvc.perform(post("/api/trade-notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"strategyId":"STR-1","runId":"RUN-1","note":"Good entry timing","tags":"timing,entry","starRating":4,"marketCondition":"TRENDING"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.strategyId").value("STR-1"))
                .andExpect(jsonPath("$.note").value("Good entry timing"))
                .andExpect(jsonPath("$.starRating").value(4));
    }

    @Test
    void createNote_blankNote_returns400() throws Exception {
        mockMvc.perform(post("/api/trade-notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"strategyId":"STR-1","note":"","starRating":3}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createNote_starRatingOutOfRange_returns400() throws Exception {
        mockMvc.perform(post("/api/trade-notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"strategyId":"STR-1","note":"Some note","starRating":6}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getNotes_byStrategyId_delegatesToService() throws Exception {
        List<TradeNote> notes = List.of(
                TradeNote.builder().id(1L).strategyId("STR-1").note("Note 1").build(),
                TradeNote.builder().id(2L).strategyId("STR-1").note("Note 2").build());

        when(tradeNoteService.getNotesByStrategy("STR-1")).thenReturn(notes);

        mockMvc.perform(get("/api/trade-notes").param("strategyId", "STR-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].note").value("Note 1"));
    }

    @Test
    void getNotes_byRunId_delegatesToService() throws Exception {
        when(tradeNoteService.getNotesByRun("RUN-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/trade-notes").param("runId", "RUN-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getNotes_byTag_delegatesToService() throws Exception {
        when(tradeNoteService.getNotesByTag("delta")).thenReturn(List.of());

        mockMvc.perform(get("/api/trade-notes").param("tag", "delta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getNotes_noParams_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/trade-notes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void updateNote_returnsUpdated() throws Exception {
        TradeNote updated = TradeNote.builder()
                .id(1L)
                .note("Updated note")
                .tags("new,updated")
                .starRating(5)
                .build();

        when(tradeNoteService.updateNote(eq(1L), any())).thenReturn(updated);

        mockMvc.perform(put("/api/trade-notes/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"note":"Updated note","tags":"new,updated","starRating":5}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("Updated note"))
                .andExpect(jsonPath("$.starRating").value(5));
    }

    @Test
    void deleteNote_returns200() throws Exception {
        mockMvc.perform(delete("/api/trade-notes/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Note deleted"));

        verify(tradeNoteService).deleteNote(1L);
    }
}
