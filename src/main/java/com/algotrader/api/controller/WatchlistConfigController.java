package com.algotrader.api.controller;

import com.algotrader.api.dto.request.WatchlistConfigRequest;
import com.algotrader.api.dto.response.WatchlistConfigResponse;
import com.algotrader.domain.model.WatchlistConfig;
import com.algotrader.mapper.WatchlistConfigMapper;
import com.algotrader.service.WatchlistConfigService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for managing watchlist subscription configurations.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET    /api/watchlist-configs} -- list all configs</li>
 *   <li>{@code POST   /api/watchlist-configs} -- create a new config</li>
 *   <li>{@code PUT    /api/watchlist-configs/{id}} -- update a config</li>
 *   <li>{@code DELETE /api/watchlist-configs/{id}} -- delete a config</li>
 *   <li>{@code PATCH  /api/watchlist-configs/{id}/toggle} -- toggle enabled/disabled</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/watchlist-configs")
public class WatchlistConfigController {

    private final WatchlistConfigService watchlistConfigService;
    private final WatchlistConfigMapper watchlistConfigMapper;

    public WatchlistConfigController(
            WatchlistConfigService watchlistConfigService, WatchlistConfigMapper watchlistConfigMapper) {
        this.watchlistConfigService = watchlistConfigService;
        this.watchlistConfigMapper = watchlistConfigMapper;
    }

    /**
     * Returns all watchlist configs (both enabled and disabled).
     */
    @GetMapping
    public ResponseEntity<List<WatchlistConfigResponse>> getAll() {
        List<WatchlistConfig> configs = watchlistConfigService.getAll();
        return ResponseEntity.ok(watchlistConfigMapper.toResponseList(configs));
    }

    /**
     * Creates a new watchlist config. Returns 409 if underlying already exists.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<WatchlistConfigResponse> create(@RequestBody @Valid WatchlistConfigRequest request) {
        WatchlistConfig watchlistConfig = watchlistConfigMapper.toDomain(request);
        if (watchlistConfig.isEnabled() && request.getEnabled() == null) {
            // Default to enabled if not specified
            watchlistConfig.setEnabled(true);
        }
        WatchlistConfig created = watchlistConfigService.create(watchlistConfig);
        return ResponseEntity.status(HttpStatus.CREATED).body(watchlistConfigMapper.toResponse(created));
    }

    /**
     * Updates an existing watchlist config.
     */
    @PutMapping("/{id}")
    public ResponseEntity<WatchlistConfigResponse> update(
            @PathVariable Long id, @RequestBody @Valid WatchlistConfigRequest request) {
        WatchlistConfig updates = watchlistConfigMapper.toDomain(request);
        WatchlistConfig updated = watchlistConfigService.update(id, updates);
        return ResponseEntity.ok(watchlistConfigMapper.toResponse(updated));
    }

    /**
     * Deletes a watchlist config.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id) {
        watchlistConfigService.delete(id);
        return ResponseEntity.ok(Map.of("message", "Watchlist config deleted"));
    }

    /**
     * Toggles the enabled state of a watchlist config.
     */
    @PatchMapping("/{id}/toggle")
    public ResponseEntity<WatchlistConfigResponse> toggle(@PathVariable Long id) {
        WatchlistConfig toggled = watchlistConfigService.toggle(id);
        return ResponseEntity.ok(watchlistConfigMapper.toResponse(toggled));
    }
}
