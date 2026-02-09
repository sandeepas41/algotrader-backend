package com.algotrader.api.controller;

import com.algotrader.api.dto.request.TradeNoteRequest;
import com.algotrader.domain.model.TradeNote;
import com.algotrader.service.TradeNoteService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for trade journal note CRUD operations.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/trade-notes} -- create a new note</li>
 *   <li>{@code GET /api/trade-notes} -- query notes by strategyId, runId, or tag</li>
 *   <li>{@code PUT /api/trade-notes/{id}} -- update a note</li>
 *   <li>{@code DELETE /api/trade-notes/{id}} -- delete a note</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/trade-notes")
public class TradeNoteController {

    private final TradeNoteService tradeNoteService;

    public TradeNoteController(TradeNoteService tradeNoteService) {
        this.tradeNoteService = tradeNoteService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TradeNote createNote(@RequestBody @Valid TradeNoteRequest request) {
        TradeNote tradeNote = TradeNote.builder()
                .strategyId(request.getStrategyId())
                .runId(request.getRunId())
                .note(request.getNote())
                .tags(request.getTags())
                .starRating(request.getStarRating())
                .marketCondition(request.getMarketCondition())
                .build();
        return tradeNoteService.createNote(tradeNote);
    }

    @GetMapping
    public List<TradeNote> getNotes(
            @RequestParam(required = false) String strategyId,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String tag) {
        if (runId != null) {
            return tradeNoteService.getNotesByRun(runId);
        }
        if (tag != null) {
            return tradeNoteService.getNotesByTag(tag);
        }
        if (strategyId != null) {
            return tradeNoteService.getNotesByStrategy(strategyId);
        }
        return List.of();
    }

    @PutMapping("/{id}")
    public TradeNote updateNote(@PathVariable Long id, @RequestBody @Valid TradeNoteRequest request) {
        TradeNote updates = TradeNote.builder()
                .note(request.getNote())
                .tags(request.getTags())
                .starRating(request.getStarRating())
                .marketCondition(request.getMarketCondition())
                .build();
        return tradeNoteService.updateNote(id, updates);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> deleteNote(@PathVariable Long id) {
        tradeNoteService.deleteNote(id);
        return Map.of("message", "Note deleted");
    }
}
