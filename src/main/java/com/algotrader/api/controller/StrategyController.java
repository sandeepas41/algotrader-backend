package com.algotrader.api.controller;

import com.algotrader.api.dto.request.AdoptRequest;
import com.algotrader.api.dto.request.DeployConfigPayload;
import com.algotrader.api.dto.request.DeployWithAdoptionRequest;
import com.algotrader.api.dto.request.DetachRequest;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.ActionType;
import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.AdjustmentAction;
import com.algotrader.domain.model.NewLegDefinition;
import com.algotrader.domain.model.Position;
import com.algotrader.entity.StrategyLegEntity;
import com.algotrader.mapper.JsonHelper;
import com.algotrader.repository.jpa.StrategyLegJpaRepository;
import com.algotrader.strategy.adoption.AdoptionResult;
import com.algotrader.strategy.adoption.PositionAdoptionService;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.BaseStrategyConfig;
import com.algotrader.strategy.base.PositionalStrategyConfig;
import com.algotrader.strategy.impl.BearCallSpreadConfig;
import com.algotrader.strategy.impl.BearPutSpreadConfig;
import com.algotrader.strategy.impl.BullCallSpreadConfig;
import com.algotrader.strategy.impl.BullPutSpreadConfig;
import com.algotrader.strategy.impl.CalendarSpreadConfig;
import com.algotrader.strategy.impl.DiagonalSpreadConfig;
import com.algotrader.strategy.impl.IronButterflyConfig;
import com.algotrader.strategy.impl.IronCondorConfig;
import com.algotrader.strategy.impl.LongStraddleConfig;
import com.algotrader.strategy.impl.NakedOptionConfig;
import com.algotrader.strategy.impl.StraddleConfig;
import com.algotrader.strategy.impl.StrangleConfig;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
 *   <li>POST /api/strategies/deploy-with-adoption -- create strategy from existing positions (no new orders)</li>
 *   <li>POST /api/strategies/{id}/arm -- arm (start monitoring)</li>
 *   <li>POST /api/strategies/{id}/pause -- pause (freeze, keep positions)</li>
 *   <li>POST /api/strategies/{id}/resume -- resume a paused strategy</li>
 *   <li>POST /api/strategies/{id}/close -- initiate close (exit positions)</li>
 *   <li>POST /api/strategies/{id}/adopt -- adopt a broker position into the strategy</li>
 *   <li>POST /api/strategies/{id}/detach -- detach a position from the strategy</li>
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
    private final PositionAdoptionService positionAdoptionService;
    private final StrategyLegJpaRepository strategyLegJpaRepository;

    public StrategyController(
            StrategyEngine strategyEngine,
            PositionAdoptionService positionAdoptionService,
            StrategyLegJpaRepository strategyLegJpaRepository) {
        this.strategyEngine = strategyEngine;
        this.positionAdoptionService = positionAdoptionService;
        this.strategyLegJpaRepository = strategyLegJpaRepository;
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
     * Request body: type, name, underlying, expiry, config (JSON string with legs/rules/lots).
     * Optional: autoArm (default false).
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> deployStrategy(@RequestBody Map<String, Object> body) {
        StrategyType type = StrategyType.valueOf((String) body.get("type"));
        String name = (String) body.get("name");
        String underlying = (String) body.getOrDefault("underlying", "NIFTY");
        String expiryStr = (String) body.get("expiry");

        LocalDate expiry = expiryStr != null ? LocalDate.parse(expiryStr) : null;

        // Parse FE config JSON properly (legs, lots, tradingMode)
        DeployConfigPayload payload = null;
        String configJson = (String) body.get("config");
        if (configJson != null) {
            payload = JsonHelper.fromJson(configJson, DeployConfigPayload.class);
        }

        int lots = payload != null ? payload.getLots() : 1;

        BaseStrategyConfig config = buildConfigForType(type, underlying, expiry, lots);

        // Check for immediate entry: all legs FIXED + LIVE trading mode
        boolean immediateEntry = false;
        List<NewLegDefinition> legConfigs = new ArrayList<>();

        if (payload != null && payload.getLegs() != null && !payload.getLegs().isEmpty()) {
            boolean allFixed = payload.getLegs().stream().allMatch(l -> "FIXED".equals(l.getStrikeType()));

            if (allFixed && "LIVE".equals(payload.getTradingMode())) {
                immediateEntry = true;
                for (DeployConfigPayload.LegPayload leg : payload.getLegs()) {
                    InstrumentType optionType = InstrumentType.valueOf(leg.getOptionType());
                    OrderSide side = leg.getQuantity() > 0 ? OrderSide.BUY : OrderSide.SELL;
                    int legLots = Math.abs(leg.getQuantity());

                    legConfigs.add(NewLegDefinition.builder()
                            .strike(leg.getFixedStrike())
                            .optionType(optionType)
                            .side(side)
                            .lots(legLots)
                            .build());
                }
            }
        }

        config.setLegConfigs(legConfigs);
        config.setImmediateEntry(immediateEntry);
        config.setBuyFirst(payload != null && Boolean.TRUE.equals(payload.getBuyFirst()));

        // Auto-arm when immediate entry is requested
        boolean autoArm = Boolean.TRUE.equals(body.get("autoArm")) || immediateEntry;

        log.info(
                "Deploying strategy: type={}, name={}, underlying={}, expiry={}, lots={}, immediateEntry={}",
                type,
                name,
                underlying,
                expiry,
                lots,
                immediateEntry);
        String strategyId = strategyEngine.deployStrategy(type, name, config, autoArm);

        return ResponseEntity.ok(Map.of("strategyId", strategyId, "message", "Strategy deployed successfully"));
    }

    /**
     * Creates a strategy from existing broker positions in a single call.
     * Deploys the strategy, adopts all specified positions, computes entry premium
     * from adopted positions' average prices, and activates for monitoring.
     * No new orders are placed.
     */
    @PostMapping("/deploy-with-adoption")
    public ResponseEntity<Map<String, Object>> deployWithAdoption(
            @Valid @RequestBody DeployWithAdoptionRequest request) {
        StrategyType type = StrategyType.valueOf(request.getType());
        int lots = 1; // Default lots — actual quantities come from adopted positions

        // Build config with type-specific defaults
        BaseStrategyConfig config = buildConfigForType(type, request.getUnderlying(), request.getExpiry(), lots);
        config.setImmediateEntry(false); // No entry orders — positions already exist

        // Deploy strategy (CREATED state, no auto-arm)
        String strategyId = strategyEngine.deployStrategy(type, request.getName(), config, false);
        BaseStrategy strategy = strategyEngine.getStrategy(strategyId);

        // Adopt each position
        List<String> allWarnings = new ArrayList<>();
        for (AdoptRequest pos : request.getPositions()) {
            AdoptionResult result =
                    positionAdoptionService.adoptPosition(strategy, pos.getPositionId(), pos.getQuantity());
            allWarnings.addAll(result.getWarnings());
        }

        // Short-circuit to ACTIVE (skip entry flow — positions already exist)
        strategyEngine.activateWithAdoptedPositions(strategyId);

        log.info(
                "Strategy deployed with adoption: id={}, type={}, name={}, positions={}, warnings={}",
                strategyId,
                type,
                request.getName(),
                request.getPositions().size(),
                allWarnings.size());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("strategyId", strategyId);
        response.put("status", "ACTIVE");
        response.put("entryPremium", strategy.getEntryPremium());
        response.put("positions", strategy.getPositions().size());
        response.put("warnings", allWarnings);
        response.put("message", "Strategy created from positions and activated");

        return ResponseEntity.ok(response);
    }

    /**
     * Builds the strategy-specific config subclass for the given type,
     * populating common fields (underlying, expiry, lots). Strategy-specific
     * fields use sensible defaults until a config editing UI is built.
     */
    private BaseStrategyConfig buildConfigForType(StrategyType type, String underlying, LocalDate expiry, int lots) {
        return switch (type) {
            case STRADDLE ->
                StraddleConfig.builder()
                        .underlying(underlying)
                        .expiry(expiry)
                        .lots(lots)
                        .build();
            case LONG_STRADDLE ->
                LongStraddleConfig.builder()
                        .underlying(underlying)
                        .expiry(expiry)
                        .lots(lots)
                        .build();
            case STRANGLE ->
                StrangleConfig.builder()
                        .underlying(underlying)
                        .expiry(expiry)
                        .lots(lots)
                        .build();
            case IRON_CONDOR ->
                IronCondorConfig.builder()
                        .underlying(underlying)
                        .expiry(expiry)
                        .lots(lots)
                        .build();
            case IRON_BUTTERFLY ->
                IronButterflyConfig.builder()
                        .underlying(underlying)
                        .expiry(expiry)
                        .lots(lots)
                        .build();
            case BULL_CALL_SPREAD ->
                BullCallSpreadConfig.builder()
                        .underlying(underlying)
                        .expiry(expiry)
                        .lots(lots)
                        .build();
            case BEAR_CALL_SPREAD ->
                BearCallSpreadConfig.builder()
                        .underlying(underlying)
                        .expiry(expiry)
                        .lots(lots)
                        .build();
            case BULL_PUT_SPREAD ->
                BullPutSpreadConfig.builder()
                        .underlying(underlying)
                        .expiry(expiry)
                        .lots(lots)
                        .build();
            case BEAR_PUT_SPREAD ->
                BearPutSpreadConfig.builder()
                        .underlying(underlying)
                        .expiry(expiry)
                        .lots(lots)
                        .build();
            case CALENDAR_SPREAD ->
                CalendarSpreadConfig.builder()
                        .underlying(underlying)
                        .expiry(expiry)
                        .lots(lots)
                        .build();
            case DIAGONAL_SPREAD ->
                DiagonalSpreadConfig.builder()
                        .underlying(underlying)
                        .expiry(expiry)
                        .lots(lots)
                        .build();
            case CE_BUY, CE_SELL, PE_BUY, PE_SELL ->
                NakedOptionConfig.builder()
                        .underlying(underlying)
                        .expiry(expiry)
                        .lots(lots)
                        .build();
            case CUSTOM ->
                PositionalStrategyConfig.builder()
                        .underlying(underlying)
                        .expiry(expiry)
                        .lots(lots)
                        .targetPercent(BigDecimal.valueOf(0.5))
                        .stopLossMultiplier(BigDecimal.valueOf(2.0))
                        .minDaysToExpiry(1)
                        .build();
        };
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

    /**
     * Adopts a broker position into a strategy by creating a StrategyLeg linked to it.
     * Validates quantity against unmanaged remainder and option type compatibility.
     */
    @PostMapping("/{id}/adopt")
    public ResponseEntity<AdoptionResult> adoptPosition(
            @PathVariable String id, @Valid @RequestBody AdoptRequest adoptRequest) {
        BaseStrategy strategy = strategyEngine.getStrategy(id);
        AdoptionResult adoptionResult = positionAdoptionService.adoptPosition(
                strategy, adoptRequest.getPositionId(), adoptRequest.getQuantity());
        return ResponseEntity.ok(adoptionResult);
    }

    /**
     * Detaches a position from a strategy by clearing the leg's positionId.
     * The leg is preserved; only the position link is severed.
     */
    @PostMapping("/{id}/detach")
    public ResponseEntity<AdoptionResult> detachPosition(
            @PathVariable String id, @Valid @RequestBody DetachRequest detachRequest) {
        BaseStrategy strategy = strategyEngine.getStrategy(id);
        AdoptionResult adoptionResult = positionAdoptionService.detachPosition(strategy, detachRequest.getPositionId());
        return ResponseEntity.ok(adoptionResult);
    }

    private Map<String, Object> buildStrategySummary(String id, BaseStrategy strategy) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", id);
        summary.put("name", strategy.getName());
        summary.put("type", strategy.getType().name());
        summary.put("status", strategy.getStatus().name());
        summary.put("underlying", strategy.getUnderlying());
        summary.put("expiry", strategy.getConfig().getExpiry());
        summary.put("positionCount", strategy.getPositions().size());
        summary.put("entryPremium", strategy.getEntryPremium());
        summary.put("deployedAt", strategy.getEntryTime());
        summary.put("totalPnl", computeTotalPnl(strategy));
        summary.put("legs", buildLegs(id));
        return summary;
    }

    private Map<String, Object> buildStrategyDetail(String id, BaseStrategy strategy) {
        Map<String, Object> detail = buildStrategySummary(id, strategy);
        detail.put("positions", strategy.getPositions());
        detail.put("lastEvaluationTime", strategy.getLastEvaluationTime());

        // Config exit parameters (type-specific)
        detail.put("config", buildConfigSummary(strategy.getConfig()));

        return detail;
    }

    /** Fetches strategy legs from H2 and maps to response shape. */
    private List<Map<String, Object>> buildLegs(String strategyId) {
        List<StrategyLegEntity> legEntities = strategyLegJpaRepository.findByStrategyId(strategyId);
        return legEntities.stream()
                .map(leg -> {
                    Map<String, Object> legMap = new LinkedHashMap<>();
                    legMap.put("id", leg.getId());
                    legMap.put("strategyId", leg.getStrategyId());
                    legMap.put("optionType", leg.getOptionType());
                    legMap.put("strike", leg.getStrike());
                    legMap.put("quantity", leg.getQuantity());
                    legMap.put("strikeSelection", leg.getStrikeSelection());
                    legMap.put("positionId", leg.getPositionId());
                    return legMap;
                })
                .toList();
    }

    /**
     * Extracts the user-relevant exit parameters from the strategy config.
     * Returns a flat map with targetPercent, stopLossMultiplier, minDaysToExpiry, etc.
     */
    private Map<String, Object> buildConfigSummary(BaseStrategyConfig config) {
        Map<String, Object> configMap = new LinkedHashMap<>();
        configMap.put("lots", config.getLots());
        configMap.put("entryStartTime", config.getEntryStartTime());
        configMap.put("entryEndTime", config.getEntryEndTime());
        configMap.put("strikeInterval", config.getStrikeInterval());
        configMap.put("autoPausePnlThreshold", config.getAutoPausePnlThreshold());
        configMap.put("autoPauseDeltaThreshold", config.getAutoPauseDeltaThreshold());

        if (config instanceof PositionalStrategyConfig positionalConfig) {
            configMap.put("targetPercent", positionalConfig.getTargetPercent());
            configMap.put("stopLossMultiplier", positionalConfig.getStopLossMultiplier());
            configMap.put("minDaysToExpiry", positionalConfig.getMinDaysToExpiry());
        }

        return configMap;
    }

    /**
     * Computes total unrealized P&L from all strategy positions.
     */
    private BigDecimal computeTotalPnl(BaseStrategy strategy) {
        return strategy.getPositions().stream()
                .map(Position::getUnrealizedPnl)
                .filter(pnl -> pnl != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
