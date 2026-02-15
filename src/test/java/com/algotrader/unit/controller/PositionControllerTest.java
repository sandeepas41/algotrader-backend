package com.algotrader.unit.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.PositionController;
import com.algotrader.broker.BrokerGateway;
import com.algotrader.config.ApiResponseAdvice;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.ReconciliationResult;
import com.algotrader.mapper.BrokerPositionMapper;
import com.algotrader.reconciliation.PositionReconciliationService;
import com.algotrader.repository.redis.PositionRedisRepository;
import com.algotrader.strategy.adoption.PositionAllocationService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    private PositionReconciliationService positionReconciliationService;

    @Mock
    private PositionAllocationService positionAllocationService;

    @Mock
    private BrokerPositionMapper brokerPositionMapper;

    @BeforeEach
    void setUp() {
        PositionController controller = new PositionController(
                positionRedisRepository,
                brokerGateway,
                positionReconciliationService,
                positionAllocationService,
                brokerPositionMapper);
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
                .build();
        Position position2 = Position.builder()
                .id("POS-002")
                .tradingSymbol("NIFTY25FEB24500PE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(85.25))
                .unrealizedPnl(BigDecimal.valueOf(-1200))
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
    @DisplayName("GET /api/positions/broker returns day and net positions with allocation data")
    void getBrokerPositionsReturnsDayAndNetWithAllocation() throws Exception {
        Position dayPos = Position.builder()
                .id("NFO:NIFTY25FEB24500CE")
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .build();
        Position netPos = Position.builder()
                .id("NFO:NIFTY25FEB24500CE")
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-100)
                .build();

        when(brokerGateway.getPositions()).thenReturn(Map.of("day", List.of(dayPos), "net", List.of(netPos)));
        when(positionAllocationService.getAllocationMap(Set.of("NFO:NIFTY25FEB24500CE")))
                .thenReturn(Map.of("NFO:NIFTY25FEB24500CE", -75));

        // Stub the mapper
        when(brokerPositionMapper.toResponse(dayPos))
                .thenReturn(com.algotrader.api.dto.response.BrokerPositionResponse.builder()
                        .id("NFO:NIFTY25FEB24500CE")
                        .tradingSymbol("NIFTY25FEB24500CE")
                        .quantity(-50)
                        .build());
        when(brokerPositionMapper.toResponse(netPos))
                .thenReturn(com.algotrader.api.dto.response.BrokerPositionResponse.builder()
                        .id("NFO:NIFTY25FEB24500CE")
                        .tradingSymbol("NIFTY25FEB24500CE")
                        .quantity(-100)
                        .build());

        mockMvc.perform(get("/api/positions/broker"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.day").isArray())
                .andExpect(jsonPath("$.data.day.length()").value(1))
                .andExpect(jsonPath("$.data.day[0].allocatedQuantity").value(-75))
                .andExpect(jsonPath("$.data.net").isArray())
                .andExpect(jsonPath("$.data.net.length()").value(1))
                .andExpect(jsonPath("$.data.net[0].allocatedQuantity").value(-75));
    }

    @Test
    @DisplayName("GET /api/positions/broker returns 0 allocatedQuantity for unmanaged positions")
    void getBrokerPositionsReturnsZeroAllocationForUnmanaged() throws Exception {
        Position netPos = Position.builder()
                .id("NFO:NIFTY25FEB24500PE")
                .tradingSymbol("NIFTY25FEB24500PE")
                .quantity(-50)
                .build();

        when(brokerGateway.getPositions()).thenReturn(Map.of("net", List.of(netPos)));
        // No allocations — empty map returned
        when(positionAllocationService.getAllocationMap(Set.of("NFO:NIFTY25FEB24500PE")))
                .thenReturn(Map.of());

        when(brokerPositionMapper.toResponse(netPos))
                .thenReturn(com.algotrader.api.dto.response.BrokerPositionResponse.builder()
                        .id("NFO:NIFTY25FEB24500PE")
                        .tradingSymbol("NIFTY25FEB24500PE")
                        .quantity(-50)
                        .build());

        mockMvc.perform(get("/api/positions/broker"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.net[0].allocatedQuantity").value(0));
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
}
