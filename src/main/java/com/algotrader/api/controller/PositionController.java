package com.algotrader.api.controller;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.ReconciliationResult;
import com.algotrader.reconciliation.PositionReconciliationService;
import com.algotrader.repository.redis.PositionRedisRepository;
import com.algotrader.strategy.adoption.AdoptionResult;
import com.algotrader.strategy.adoption.PositionAdoptionService;
import com.algotrader.strategy.base.BaseStrategy;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for position management: listing, closing, reconciliation, and adoption.
 *
 * <p>Positions are read from Redis (real-time) via PositionRedisRepository.
 * Broker-reported positions come from BrokerGateway.getPositions().
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/positions -- list all positions from Redis</li>
 *   <li>GET /api/positions/broker -- list raw broker positions (day + net)</li>
 *   <li>GET /api/positions/orphans -- list positions not assigned to any strategy</li>
 *   <li>POST /api/positions/reconcile -- trigger on-demand reconciliation</li>
 *   <li>POST /api/positions/{positionId}/adopt -- adopt position into strategy</li>
 *   <li>POST /api/positions/{positionId}/detach -- detach position from strategy</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/positions")
public class PositionController {

    private static final Logger log = LoggerFactory.getLogger(PositionController.class);

    private final PositionRedisRepository positionRedisRepository;
    private final BrokerGateway brokerGateway;
    private final PositionAdoptionService positionAdoptionService;
    private final StrategyEngine strategyEngine;
    private final PositionReconciliationService positionReconciliationService;

    public PositionController(
            PositionRedisRepository positionRedisRepository,
            BrokerGateway brokerGateway,
            PositionAdoptionService positionAdoptionService,
            StrategyEngine strategyEngine,
            PositionReconciliationService positionReconciliationService) {
        this.positionRedisRepository = positionRedisRepository;
        this.brokerGateway = brokerGateway;
        this.positionAdoptionService = positionAdoptionService;
        this.strategyEngine = strategyEngine;
        this.positionReconciliationService = positionReconciliationService;
    }

    /**
     * Returns all positions from Redis (real-time in-memory store).
     */
    @GetMapping
    public ResponseEntity<List<Position>> listPositions() {
        List<Position> positions = positionRedisRepository.findAll();
        return ResponseEntity.ok(positions);
    }

    /**
     * Returns raw broker positions grouped by "day" and "net".
     */
    @GetMapping("/broker")
    public ResponseEntity<Map<String, List<Position>>> getBrokerPositions() {
        Map<String, List<Position>> positions = brokerGateway.getPositions();
        return ResponseEntity.ok(positions);
    }

    /**
     * Returns positions not assigned to any strategy (orphans).
     */
    @GetMapping("/orphans")
    public ResponseEntity<List<Position>> getOrphanPositions() {
        List<Position> orphans = positionAdoptionService.findOrphanPositions();
        return ResponseEntity.ok(orphans);
    }

    /**
     * Triggers on-demand position reconciliation: compares broker vs Redis,
     * resolves mismatches, and returns the full reconciliation result.
     */
    @PostMapping("/reconcile")
    public ResponseEntity<ReconciliationResult> reconcile() {
        log.info("Manual position reconciliation triggered");
        ReconciliationResult reconciliationResult = positionReconciliationService.manualReconcile();
        return ResponseEntity.ok(reconciliationResult);
    }

    /**
     * Adopts a position into a strategy for tracking and management.
     * Request body must contain "strategyId".
     */
    @PostMapping("/{positionId}/adopt")
    public ResponseEntity<AdoptionResult> adoptPosition(
            @PathVariable String positionId, @RequestBody Map<String, String> body) {
        String strategyId = body.get("strategyId");
        BaseStrategy strategy = strategyEngine.getStrategy(strategyId);
        AdoptionResult result = positionAdoptionService.adoptPosition(strategy, positionId);
        return ResponseEntity.ok(result);
    }

    /**
     * Detaches a position from its owning strategy without closing it.
     * Request body must contain "strategyId".
     */
    @PostMapping("/{positionId}/detach")
    public ResponseEntity<AdoptionResult> detachPosition(
            @PathVariable String positionId, @RequestBody Map<String, String> body) {
        String strategyId = body.get("strategyId");
        BaseStrategy strategy = strategyEngine.getStrategy(strategyId);
        AdoptionResult result = positionAdoptionService.detachPosition(strategy, positionId);
        return ResponseEntity.ok(result);
    }
}
