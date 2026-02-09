package com.algotrader.unit.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.PositionController;
import com.algotrader.broker.BrokerGateway;
import com.algotrader.config.ApiResponseAdvice;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.ReconciliationResult;
import com.algotrader.reconciliation.PositionReconciliationService;
import com.algotrader.repository.redis.PositionRedisRepository;
import com.algotrader.strategy.adoption.AdoptionResult;
import com.algotrader.strategy.adoption.PositionAdoptionService;
import com.algotrader.strategy.base.BaseStrategy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc tests for the PositionController.
 * Uses MockMvcBuilders.standaloneSetup since @WebMvcTest was removed in Spring Boot 4.0.
 */
@ExtendWith(MockitoExtension.class)
class PositionControllerTest {

    private MockMvc mockMvc;

    @Mock
    private PositionRedisRepository positionRedisRepository;

    @Mock
    private BrokerGateway brokerGateway;

    @Mock
    private PositionAdoptionService positionAdoptionService;

    @Mock
    private StrategyEngine strategyEngine;

    @Mock
    private PositionReconciliationService positionReconciliationService;

    @Mock
    private BaseStrategy baseStrategy;

    @BeforeEach
    void setUp() {
        PositionController controller = new PositionController(
                positionRedisRepository,
                brokerGateway,
                positionAdoptionService,
                strategyEngine,
                positionReconciliationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiResponseAdvice())
                .build();
    }

    @Test
    @DisplayName("GET /api/positions returns all positions from Redis")
    void listPositionsReturnsRedisData() throws Exception {
        Position position1 = Position.builder()
                .id("POS-001")
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(120.50))
                .unrealizedPnl(BigDecimal.valueOf(2500))
                .strategyId("STR-001")
                .build();
        Position position2 = Position.builder()
                .id("POS-002")
                .tradingSymbol("NIFTY25FEB24500PE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(85.25))
                .unrealizedPnl(BigDecimal.valueOf(-1200))
                .strategyId("STR-001")
                .build();

        when(positionRedisRepository.findAll()).thenReturn(List.of(position1, position2));

        mockMvc.perform(get("/api/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].tradingSymbol").value("NIFTY25FEB24500CE"))
                .andExpect(jsonPath("$.data[0].quantity").value(-50))
                .andExpect(jsonPath("$.data[1].tradingSymbol").value("NIFTY25FEB24500PE"));
    }

    @Test
    @DisplayName("GET /api/positions/broker returns day and net positions from broker")
    void getBrokerPositionsReturnsDayAndNet() throws Exception {
        Position dayPos = Position.builder()
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .build();
        Position netPos = Position.builder()
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-100)
                .build();

        when(brokerGateway.getPositions()).thenReturn(Map.of("day", List.of(dayPos), "net", List.of(netPos)));

        mockMvc.perform(get("/api/positions/broker"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.day").isArray())
                .andExpect(jsonPath("$.data.day.length()").value(1))
                .andExpect(jsonPath("$.data.net").isArray())
                .andExpect(jsonPath("$.data.net.length()").value(1));
    }

    @Test
    @DisplayName("GET /api/positions/orphans returns unassigned positions")
    void getOrphanPositionsReturnsOrphans() throws Exception {
        Position orphan = Position.builder()
                .id("POS-003")
                .tradingSymbol("BANKNIFTY25FEB50000CE")
                .quantity(-25)
                .build();

        when(positionAdoptionService.findOrphanPositions()).thenReturn(List.of(orphan));

        mockMvc.perform(get("/api/positions/orphans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("POS-003"));
    }

    @Test
    @DisplayName("POST /api/positions/reconcile triggers reconciliation and returns result")
    void reconcileReturnsBrokerAndRedisComparison() throws Exception {
        ReconciliationResult result = ReconciliationResult.builder()
                .timestamp(LocalDateTime.now())
                .trigger("MANUAL")
                .brokerPositionCount(1)
                .localPositionCount(1)
                .durationMs(50)
                .build();
        when(positionReconciliationService.manualReconcile()).thenReturn(result);

        mockMvc.perform(post("/api/positions/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.brokerPositionCount").value(1))
                .andExpect(jsonPath("$.data.localPositionCount").value(1))
                .andExpect(jsonPath("$.data.trigger").value("MANUAL"));
    }

    @Test
    @DisplayName("POST /api/positions/reconcile reports mismatches")
    void reconcileDetectsMismatch() throws Exception {
        ReconciliationResult result = ReconciliationResult.builder()
                .timestamp(LocalDateTime.now())
                .trigger("MANUAL")
                .brokerPositionCount(2)
                .localPositionCount(1)
                .autoSynced(1)
                .durationMs(30)
                .build();
        when(positionReconciliationService.manualReconcile()).thenReturn(result);

        mockMvc.perform(post("/api/positions/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.brokerPositionCount").value(2))
                .andExpect(jsonPath("$.data.localPositionCount").value(1))
                .andExpect(jsonPath("$.data.autoSynced").value(1));
    }

    @Test
    @DisplayName("POST /api/positions/{positionId}/adopt adopts position into strategy")
    void adoptPositionReturnsResult() throws Exception {
        when(strategyEngine.getStrategy("STR-001")).thenReturn(baseStrategy);
        AdoptionResult result = AdoptionResult.builder()
                .strategyId("STR-001")
                .positionId("POS-001")
                .operationType(AdoptionResult.OperationType.ADOPT)
                .recalculatedEntryPremium(BigDecimal.valueOf(205.75))
                .build();
        when(positionAdoptionService.adoptPosition(baseStrategy, "POS-001")).thenReturn(result);

        String body = """
                { "strategyId": "STR-001" }
                """;

        mockMvc.perform(post("/api/positions/POS-001/adopt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.strategyId").value("STR-001"))
                .andExpect(jsonPath("$.data.positionId").value("POS-001"))
                .andExpect(jsonPath("$.data.operationType").value("ADOPT"))
                .andExpect(jsonPath("$.data.recalculatedEntryPremium").value(205.75));
    }

    @Test
    @DisplayName("POST /api/positions/{positionId}/detach detaches position from strategy")
    void detachPositionReturnsResult() throws Exception {
        when(strategyEngine.getStrategy("STR-001")).thenReturn(baseStrategy);
        AdoptionResult result = AdoptionResult.builder()
                .strategyId("STR-001")
                .positionId("POS-002")
                .operationType(AdoptionResult.OperationType.DETACH)
                .build();
        when(positionAdoptionService.detachPosition(baseStrategy, "POS-002")).thenReturn(result);

        String body = """
                { "strategyId": "STR-001" }
                """;

        mockMvc.perform(post("/api/positions/POS-002/detach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.strategyId").value("STR-001"))
                .andExpect(jsonPath("$.data.positionId").value("POS-002"))
                .andExpect(jsonPath("$.data.operationType").value("DETACH"));
    }
}
