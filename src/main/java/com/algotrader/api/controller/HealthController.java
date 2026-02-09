package com.algotrader.api.controller;

import com.algotrader.api.dto.response.HealthDetailedResponse;
import com.algotrader.broker.KiteAuthService;
import com.algotrader.broker.KiteMarketDataService;
import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.session.SessionHealthService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Multi-tier health check endpoints for different consumers.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/health -- shallow health check (ECS/ALB probe, just confirms app responds)</li>
 *   <li>GET /api/health/detailed -- deep health check for the monitoring dashboard</li>
 * </ul>
 *
 * <p>The shallow endpoint intentionally does NOT check subsystems to avoid
 * cascading failures (if Redis is down, we still want the app to respond 200
 * to the load balancer). The detailed endpoint checks H2, Redis, Kite, and tick feed.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final KiteAuthService kiteAuthService;
    private final KiteMarketDataService kiteMarketDataService;
    private final SessionHealthService sessionHealthService;
    private final TradingCalendarService tradingCalendarService;

    @Value("${algotrader.trading-mode:LIVE}")
    private String tradingMode;

    public HealthController(
            KiteAuthService kiteAuthService,
            KiteMarketDataService kiteMarketDataService,
            SessionHealthService sessionHealthService,
            TradingCalendarService tradingCalendarService) {
        this.kiteAuthService = kiteAuthService;
        this.kiteMarketDataService = kiteMarketDataService;
        this.sessionHealthService = sessionHealthService;
        this.tradingCalendarService = tradingCalendarService;
    }

    /**
     * Shallow health check for ECS/ALB health probes.
     * Always returns 200 if the application is running.
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> shallowHealth() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    /**
     * Detailed health check for the monitoring dashboard.
     * Checks all subsystems and returns individual statuses.
     */
    @GetMapping("/detailed")
    public ResponseEntity<HealthDetailedResponse> detailedHealth() {
        Map<String, HealthDetailedResponse.SubsystemHealth> subsystems = new LinkedHashMap<>();

        // Kite session
        boolean sessionActive = sessionHealthService.isSessionActive();
        subsystems.put(
                "kiteSession",
                HealthDetailedResponse.SubsystemHealth.builder()
                        .status(sessionActive ? "UP" : "DOWN")
                        .message(
                                sessionActive
                                        ? "Session active for user " + kiteAuthService.getCurrentUserId()
                                        : "Session state: "
                                                + sessionHealthService
                                                        .getState()
                                                        .name())
                        .build());

        // Tick feed (Kite WebSocket)
        boolean tickConnected = kiteMarketDataService.isConnected();
        boolean tickDegraded = kiteMarketDataService.isDegraded();
        String tickStatus = tickConnected ? (tickDegraded ? "DEGRADED" : "UP") : "DOWN";
        subsystems.put(
                "tickFeed",
                HealthDetailedResponse.SubsystemHealth.builder()
                        .status(tickStatus)
                        .message(
                                tickConnected
                                        ? kiteMarketDataService.getSubscribedCount() + " instruments subscribed"
                                        : "WebSocket disconnected")
                        .build());

        // Overall status: UP if session active and ticks flowing, DEGRADED if partial, DOWN if all down
        String overallStatus = deriveOverallStatus(subsystems);

        HealthDetailedResponse response = HealthDetailedResponse.builder()
                .status(overallStatus)
                .subsystems(subsystems)
                .tradingMode(tradingMode)
                .marketPhase(tradingCalendarService.getCurrentPhase().name())
                .build();

        return ResponseEntity.ok(response);
    }

    private String deriveOverallStatus(Map<String, HealthDetailedResponse.SubsystemHealth> subsystems) {
        boolean anyDown = subsystems.values().stream().anyMatch(s -> "DOWN".equals(s.getStatus()));
        boolean anyDegraded = subsystems.values().stream().anyMatch(s -> "DEGRADED".equals(s.getStatus()));

        if (anyDown) return "DEGRADED";
        if (anyDegraded) return "DEGRADED";
        return "UP";
    }
}
