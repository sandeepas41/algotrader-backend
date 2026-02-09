package com.algotrader.api.controller;

import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.ActionType;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.AdjustmentAction;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.BaseStrategyConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for strategy lifecycle management.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/strategies -- list all active strategies</li>
 *   <li>GET /api/strategies/{id} -- get strategy details</li>
 *   <li>POST /api/strategies -- deploy a new strategy</li>
 *   <li>POST /api/strategies/{id}/arm -- arm (start monitoring)</li>
 *   <li>POST /api/strategies/{id}/pause -- pause (freeze, keep positions)</li>
 *   <li>POST /api/strategies/{id}/resume -- resume a paused strategy</li>
 *   <li>POST /api/strategies/{id}/close -- initiate close (exit positions)</li>
 *   <li>POST /api/strategies/{id}/force-adjust -- force manual adjustment</li>
 *   <li>POST /api/strategies/pause-all -- pause all active strategies</li>
 *   <li>DELETE /api/strategies/{id} -- undeploy a closed strategy</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/strategies")
public class StrategyController {

    private static final Logger log = LoggerFactory.getLogger(StrategyController.class);

    private final StrategyEngine strategyEngine;

    public StrategyController(StrategyEngine strategyEngine) {
        this.strategyEngine = strategyEngine;
    }

    /**
     * Returns all active strategies with their current status.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listStrategies() {
        List<Map<String, Object>> strategies = strategyEngine.getActiveStrategies().entrySet().stream()
                .map(entry -> buildStrategySummary(entry.getKey(), entry.getValue()))
                .toList();
        return ResponseEntity.ok(strategies);
    }

    /**
     * Returns detailed information about a specific strategy.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getStrategy(@PathVariable String id) {
        BaseStrategy strategy = strategyEngine.getStrategy(id);
        return ResponseEntity.ok(buildStrategyDetail(id, strategy));
    }

    /**
     * Deploys a new strategy instance.
     * Request body must contain: type, name. Optional: autoArm (default false).
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> deployStrategy(@RequestBody Map<String, Object> body) {
        StrategyType type = StrategyType.valueOf((String) body.get("type"));
        String name = (String) body.get("name");
        boolean autoArm = Boolean.TRUE.equals(body.get("autoArm"));

        // #TODO: Build strategy-specific config from request body (StraddleConfig, IronCondorConfig, etc.)
        BaseStrategyConfig config = BaseStrategyConfig.builder()
                .underlying((String) body.getOrDefault("underlying", "NIFTY"))
                .build();

        log.info("Deploying strategy: type={}, name={}, autoArm={}", type, name, autoArm);
        String strategyId = strategyEngine.deployStrategy(type, name, config, autoArm);

        return ResponseEntity.ok(Map.of("strategyId", strategyId, "message", "Strategy deployed successfully"));
    }

    /**
     * Arms a strategy, transitioning it from CREATED to ARMED.
     */
    @PostMapping("/{id}/arm")
    public ResponseEntity<Map<String, String>> armStrategy(@PathVariable String id) {
        strategyEngine.armStrategy(id);
        return ResponseEntity.ok(Map.of("message", "Strategy armed", "strategyId", id));
    }

    /**
     * Pauses a strategy. Positions remain open.
     */
    @PostMapping("/{id}/pause")
    public ResponseEntity<Map<String, String>> pauseStrategy(@PathVariable String id) {
        strategyEngine.pauseStrategy(id);
        return ResponseEntity.ok(Map.of("message", "Strategy paused", "strategyId", id));
    }

    /**
     * Resumes a paused strategy.
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, String>> resumeStrategy(@PathVariable String id) {
        strategyEngine.resumeStrategy(id);
        return ResponseEntity.ok(Map.of("message", "Strategy resumed", "strategyId", id));
    }

    /**
     * Initiates strategy close (begins exit order execution).
     */
    @PostMapping("/{id}/close")
    public ResponseEntity<Map<String, String>> closeStrategy(@PathVariable String id) {
        strategyEngine.closeStrategy(id);
        return ResponseEntity.ok(Map.of("message", "Strategy close initiated", "strategyId", id));
    }

    /**
     * Forces a manual adjustment on an active strategy, bypassing cooldown.
     */
    @PostMapping("/{id}/force-adjust")
    public ResponseEntity<Map<String, String>> forceAdjust(
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        ActionType actionType = ActionType.valueOf((String) body.get("actionType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) body.getOrDefault("parameters", Map.of());

        AdjustmentAction action = AdjustmentAction.builder()
                .type(actionType)
                .parameters(parameters)
                .build();

        strategyEngine.forceAdjustment(id, action);
        return ResponseEntity.ok(Map.of("message", "Adjustment triggered", "strategyId", id));
    }

    /**
     * Pauses all active strategies (emergency freeze without closing positions).
     */
    @PostMapping("/pause-all")
    public ResponseEntity<Map<String, String>> pauseAll() {
        log.info("Pause all strategies requested");
        strategyEngine.pauseAll();
        return ResponseEntity.ok(Map.of("message", "All strategies paused"));
    }

    /**
     * Undeploys a closed strategy, removing it from the active registry.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> undeployStrategy(@PathVariable String id) {
        strategyEngine.undeployStrategy(id);
        return ResponseEntity.ok(Map.of("message", "Strategy undeployed", "strategyId", id));
    }

    private Map<String, Object> buildStrategySummary(String id, BaseStrategy strategy) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", id);
        summary.put("name", strategy.getName());
        summary.put("type", strategy.getType().name());
        summary.put("status", strategy.getStatus().name());
        summary.put("underlying", strategy.getUnderlying());
        summary.put("positionCount", strategy.getPositions().size());
        return summary;
    }

    private Map<String, Object> buildStrategyDetail(String id, BaseStrategy strategy) {
        Map<String, Object> detail = buildStrategySummary(id, strategy);
        detail.put("positions", strategy.getPositions());
        detail.put("lastEvaluationTime", strategy.getLastEvaluationTime());
        detail.put("entryPremium", strategy.getEntryPremium());
        return detail;
    }
}
