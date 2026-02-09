package com.algotrader.api.controller;

import com.algotrader.risk.AccountRiskChecker;
import com.algotrader.risk.KillSwitchResult;
import com.algotrader.risk.KillSwitchService;
import com.algotrader.risk.RiskLimitPersistenceService;
import com.algotrader.risk.RiskLimits;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for risk management: status, limits, kill switch.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/risk/status -- current risk status (daily P&L, limits, breaches)</li>
 *   <li>GET /api/risk/limits -- current risk limit configuration</li>
 *   <li>PUT /api/risk/limits -- update risk limits</li>
 *   <li>POST /api/risk/kill-switch -- activate kill switch (requires "CONFIRM" text)</li>
 *   <li>POST /api/risk/kill-switch/deactivate -- deactivate kill switch</li>
 *   <li>POST /api/risk/pause-all -- pause all strategies without closing positions</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/risk")
public class RiskController {

    private static final Logger log = LoggerFactory.getLogger(RiskController.class);

    private final AccountRiskChecker accountRiskChecker;
    private final KillSwitchService killSwitchService;
    private final RiskLimits riskLimits;
    private final RiskLimitPersistenceService riskLimitPersistenceService;

    public RiskController(
            AccountRiskChecker accountRiskChecker,
            KillSwitchService killSwitchService,
            RiskLimits riskLimits,
            RiskLimitPersistenceService riskLimitPersistenceService) {
        this.accountRiskChecker = accountRiskChecker;
        this.killSwitchService = killSwitchService;
        this.riskLimits = riskLimits;
        this.riskLimitPersistenceService = riskLimitPersistenceService;
    }

    /**
     * Returns current risk status including daily P&L, limit proximity, and kill switch state.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getRiskStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("dailyRealizedPnl", accountRiskChecker.getDailyRealisedPnl());
        status.put("dailyLimitApproaching", accountRiskChecker.isDailyLimitApproaching());
        status.put("dailyLimitBreached", accountRiskChecker.isDailyLimitBreached());
        status.put("openPositionCount", accountRiskChecker.getOpenPositionCount());
        status.put("pendingOrderCount", accountRiskChecker.getPendingOrderCount());
        status.put("killSwitchActive", killSwitchService.isActive());
        return ResponseEntity.ok(status);
    }

    /**
     * Returns current risk limit configuration.
     */
    @GetMapping("/limits")
    public ResponseEntity<RiskLimits> getRiskLimits() {
        return ResponseEntity.ok(riskLimits);
    }

    /**
     * Updates risk limits. Only non-null fields in the request body are applied.
     * Changes are persisted to H2 for audit trail.
     */
    @PutMapping("/limits")
    public ResponseEntity<RiskLimits> updateRiskLimits(@RequestBody Map<String, Object> body) {
        log.info("Risk limits update requested: {}", body.keySet());

        if (body.containsKey("dailyLossLimit")) {
            BigDecimal oldValue = riskLimits.getDailyLossLimit();
            BigDecimal newValue = new BigDecimal(body.get("dailyLossLimit").toString());
            riskLimits.setDailyLossLimit(newValue);
            riskLimitPersistenceService.recordChange(
                    "GLOBAL", "dailyLossLimit", String.valueOf(oldValue), String.valueOf(newValue), "API", null);
        }

        if (body.containsKey("maxLossPerPosition")) {
            BigDecimal oldValue = riskLimits.getMaxLossPerPosition();
            BigDecimal newValue = new BigDecimal(body.get("maxLossPerPosition").toString());
            riskLimits.setMaxLossPerPosition(newValue);
            riskLimitPersistenceService.recordChange(
                    "GLOBAL", "maxLossPerPosition", String.valueOf(oldValue), String.valueOf(newValue), "API", null);
        }

        if (body.containsKey("maxOpenPositions")) {
            Integer oldValue = riskLimits.getMaxOpenPositions();
            Integer newValue = ((Number) body.get("maxOpenPositions")).intValue();
            riskLimits.setMaxOpenPositions(newValue);
            riskLimitPersistenceService.recordChange(
                    "GLOBAL", "maxOpenPositions", String.valueOf(oldValue), String.valueOf(newValue), "API", null);
        }

        if (body.containsKey("maxOpenOrders")) {
            Integer oldValue = riskLimits.getMaxOpenOrders();
            Integer newValue = ((Number) body.get("maxOpenOrders")).intValue();
            riskLimits.setMaxOpenOrders(newValue);
            riskLimitPersistenceService.recordChange(
                    "GLOBAL", "maxOpenOrders", String.valueOf(oldValue), String.valueOf(newValue), "API", null);
        }

        if (body.containsKey("maxActiveStrategies")) {
            Integer oldValue = riskLimits.getMaxActiveStrategies();
            Integer newValue = ((Number) body.get("maxActiveStrategies")).intValue();
            riskLimits.setMaxActiveStrategies(newValue);
            riskLimitPersistenceService.recordChange(
                    "GLOBAL", "maxActiveStrategies", String.valueOf(oldValue), String.valueOf(newValue), "API", null);
        }

        return ResponseEntity.ok(riskLimits);
    }

    /**
     * Activates the kill switch. Requires "confirm": "CONFIRM" in the request body
     * to prevent accidental activation. Pauses all strategies, cancels all orders,
     * and closes all positions with market orders.
     */
    @PostMapping("/kill-switch")
    public ResponseEntity<Object> activateKillSwitch(@RequestBody Map<String, String> body) {
        String confirm = body.get("confirm");
        if (!"CONFIRM".equals(confirm)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Kill switch activation requires 'confirm': 'CONFIRM' in request body"));
        }

        String reason = body.getOrDefault("reason", "Manual activation via API");
        log.error("KILL SWITCH ACTIVATION REQUESTED: {}", reason);

        KillSwitchResult result = killSwitchService.activate(reason);
        return ResponseEntity.ok(result);
    }

    /**
     * Deactivates the kill switch, allowing normal trading to resume.
     */
    @PostMapping("/kill-switch/deactivate")
    public ResponseEntity<Map<String, String>> deactivateKillSwitch() {
        killSwitchService.deactivate();
        return ResponseEntity.ok(Map.of("message", "Kill switch deactivated"));
    }

    /**
     * Pauses all strategies without closing positions (less drastic than kill switch).
     */
    @PostMapping("/pause-all")
    public ResponseEntity<Map<String, Object>> pauseAllStrategies() {
        int paused = killSwitchService.pauseAllStrategies();
        return ResponseEntity.ok(Map.of("message", "All strategies paused", "strategiesPaused", paused));
    }
}
