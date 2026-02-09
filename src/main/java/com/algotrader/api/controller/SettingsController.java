package com.algotrader.api.controller;

import com.algotrader.api.dto.response.SettingsResponse;
import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.observability.DecisionArchiveService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for runtime system settings.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/settings -- returns current settings (trading mode, market status, persist-debug)</li>
 *   <li>POST /api/settings/persist-debug -- toggles debug-level decision log persistence</li>
 * </ul>
 *
 * <p>Trading mode is read-only (configured via application.properties). Only the
 * persist-debug toggle can be changed at runtime, controlling whether DEBUG-severity
 * decision logs are persisted to H2 or only kept in the ring buffer.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final TradingCalendarService tradingCalendarService;
    private final DecisionArchiveService decisionArchiveService;

    @Value("${algotrader.trading-mode:LIVE}")
    private String tradingMode;

    public SettingsController(
            TradingCalendarService tradingCalendarService, DecisionArchiveService decisionArchiveService) {
        this.tradingCalendarService = tradingCalendarService;
        this.decisionArchiveService = decisionArchiveService;
    }

    /**
     * Returns current system settings.
     */
    @GetMapping
    public ResponseEntity<SettingsResponse> getSettings() {
        SettingsResponse response = SettingsResponse.builder()
                .tradingMode(tradingMode)
                .marketPhase(tradingCalendarService.getCurrentPhase().name())
                .marketOpen(tradingCalendarService.isMarketOpen())
                .persistDebugEnabled(decisionArchiveService.isPersistDebug())
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Toggles the persist-debug setting for decision log archival.
     * When enabled, DEBUG-severity decisions are also persisted to H2.
     */
    @PostMapping("/persist-debug")
    public ResponseEntity<Map<String, Object>> togglePersistDebug(@RequestBody Map<String, Boolean> body) {
        boolean enabled = body.getOrDefault("enabled", false);
        decisionArchiveService.setPersistDebug(enabled);
        log.info("Persist-debug toggled to: {}", enabled);
        return ResponseEntity.ok(Map.of("persistDebugEnabled", enabled));
    }
}
