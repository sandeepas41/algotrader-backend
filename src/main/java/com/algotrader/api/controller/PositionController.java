package com.algotrader.api.controller;

import com.algotrader.api.dto.response.BrokerPositionResponse;
import com.algotrader.broker.BrokerGateway;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.ReconciliationResult;
import com.algotrader.mapper.BrokerPositionMapper;
import com.algotrader.reconciliation.PositionReconciliationService;
import com.algotrader.repository.redis.PositionRedisRepository;
import com.algotrader.strategy.adoption.PositionAllocationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for position management: listing, broker positions, and reconciliation.
 *
 * <p>Positions are read from Redis (real-time) via PositionRedisRepository.
 * Broker-reported positions come from BrokerGateway.getPositions().
 *
 * <p>Adopt/detach operations moved to StrategyController (position-strategy linking
 * is now managed via strategy legs, not position.strategyId).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/positions -- list all positions from Redis</li>
 *   <li>GET /api/positions/broker -- list raw broker positions (day + net)</li>
 *   <li>POST /api/positions/reconcile -- trigger on-demand reconciliation</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/positions")
public class PositionController {

    private static final Logger log = LoggerFactory.getLogger(PositionController.class);

    private final PositionRedisRepository positionRedisRepository;
    private final BrokerGateway brokerGateway;
    private final PositionReconciliationService positionReconciliationService;
    private final PositionAllocationService positionAllocationService;
    private final BrokerPositionMapper brokerPositionMapper;

    public PositionController(
            PositionRedisRepository positionRedisRepository,
            BrokerGateway brokerGateway,
            PositionReconciliationService positionReconciliationService,
            PositionAllocationService positionAllocationService,
            BrokerPositionMapper brokerPositionMapper) {
        this.positionRedisRepository = positionRedisRepository;
        this.brokerGateway = brokerGateway;
        this.positionReconciliationService = positionReconciliationService;
        this.positionAllocationService = positionAllocationService;
        this.brokerPositionMapper = brokerPositionMapper;
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
     * Returns broker positions grouped by "day" and "net", enriched with allocatedQuantity
     * per position (sum of strategy leg quantities linked to each position).
     */
    @GetMapping("/broker")
    public ResponseEntity<Map<String, List<BrokerPositionResponse>>> getBrokerPositions() {
        Map<String, List<Position>> positions = brokerGateway.getPositions();

        // Collect all position IDs across day + net for a single batch allocation query
        Set<String> allPositionIds = positions.values().stream()
                .flatMap(List::stream)
                .map(Position::getId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        Map<String, Integer> allocationMap =
                allPositionIds.isEmpty() ? Map.of() : positionAllocationService.getAllocationMap(allPositionIds);

        // Map each group to enriched DTOs
        Map<String, List<BrokerPositionResponse>> enriched = new LinkedHashMap<>();
        for (Map.Entry<String, List<Position>> entry : positions.entrySet()) {
            List<BrokerPositionResponse> responses = entry.getValue().stream()
                    .map(position -> {
                        BrokerPositionResponse brokerPositionResponse = brokerPositionMapper.toResponse(position);
                        brokerPositionResponse.setAllocatedQuantity(allocationMap.getOrDefault(position.getId(), 0));
                        return brokerPositionResponse;
                    })
                    .toList();
            enriched.put(entry.getKey(), responses);
        }

        return ResponseEntity.ok(enriched);
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
}
