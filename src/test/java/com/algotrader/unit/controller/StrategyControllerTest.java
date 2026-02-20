package com.algotrader.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.StrategyController;
import com.algotrader.config.ApiResponseAdvice;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.AdjustmentAction;
import com.algotrader.domain.model.Position;
import com.algotrader.mapper.AdjustmentRuleMapper;
import com.algotrader.morph.MorphService;
import com.algotrader.morph.MorphTargetResolver;
import com.algotrader.repository.jpa.AdjustmentRuleJpaRepository;
import com.algotrader.repository.jpa.StrategyLegJpaRepository;
import com.algotrader.strategy.LegOperationService;
import com.algotrader.strategy.adoption.PositionAdoptionService;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.BaseStrategyConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
 * Standalone MockMvc tests for the StrategyController.
 * Uses MockMvcBuilders.standaloneSetup since @WebMvcTest was removed in Spring Boot 4.0.
 */
@ExtendWith(MockitoExtension.class)
class StrategyControllerTest {

    private MockMvc mockMvc;

    @Mock
    private StrategyEngine strategyEngine;

    @Mock
    private PositionAdoptionService positionAdoptionService;

    @Mock
    private LegOperationService legOperationService;

    @Mock
    private StrategyLegJpaRepository strategyLegJpaRepository;

    @Mock
    private AdjustmentRuleJpaRepository adjustmentRuleJpaRepository;

    @Mock
    private AdjustmentRuleMapper adjustmentRuleMapper;

    @Mock
    private MorphTargetResolver morphTargetResolver;

    @Mock
    private MorphService morphService;

    @Mock
    private BaseStrategy straddleStrategy;

    @Mock
    private BaseStrategy ironCondorStrategy;

    @BeforeEach
    void setUp() {
        StrategyController controller = new StrategyController(
                strategyEngine,
                positionAdoptionService,
                legOperationService,
                strategyLegJpaRepository,
                adjustmentRuleJpaRepository,
                adjustmentRuleMapper,
                morphTargetResolver,
                morphService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiResponseAdvice())
                .build();
    }

    @Test
    @DisplayName("GET /api/strategies returns all active strategies")
    void listStrategiesReturnsAllActive() throws Exception {
        BaseStrategyConfig config = BaseStrategyConfig.builder()
                .underlying("NIFTY")
                .expiry(LocalDate.of(2025, 2, 27))
                .build();
        when(straddleStrategy.getName()).thenReturn("Morning Straddle");
        when(straddleStrategy.getType()).thenReturn(StrategyType.STRADDLE);
        when(straddleStrategy.getStatus()).thenReturn(StrategyStatus.ACTIVE);
        when(straddleStrategy.getUnderlying()).thenReturn("NIFTY");
        when(straddleStrategy.getConfig()).thenReturn(config);
        when(straddleStrategy.getPositions())
                .thenReturn(List.of(
                        Position.builder()
                                .instrumentToken(12345L)
                                .tradingSymbol("NIFTY25FEB24500CE")
                                .averagePrice(BigDecimal.valueOf(100))
                                .quantity(-50)
                                .build(),
                        Position.builder()
                                .instrumentToken(12346L)
                                .tradingSymbol("NIFTY25FEB24500PE")
                                .averagePrice(BigDecimal.valueOf(110))
                                .quantity(-50)
                                .build()));

        Map<String, BaseStrategy> strategies = new LinkedHashMap<>();
        strategies.put("STR-001", straddleStrategy);
        when(strategyEngine.getActiveStrategies()).thenReturn(strategies);
        when(strategyLegJpaRepository.findByStrategyId("STR-001")).thenReturn(List.of());

        mockMvc.perform(get("/api/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("STR-001"))
                .andExpect(jsonPath("$.data[0].name").value("Morning Straddle"))
                .andExpect(jsonPath("$.data[0].type").value("STRADDLE"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].underlying").value("NIFTY"))
                .andExpect(jsonPath("$.data[0].positionCount").value(2));
    }

    @Test
    @DisplayName("GET /api/strategies/{id} returns strategy detail")
    void getStrategyReturnsDetail() throws Exception {
        Position pos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .averagePrice(BigDecimal.valueOf(100))
                .quantity(-50)
                .build();
        BaseStrategyConfig config = BaseStrategyConfig.builder()
                .underlying("NIFTY")
                .expiry(LocalDate.of(2025, 2, 27))
                .build();
        when(straddleStrategy.getName()).thenReturn("Morning Straddle");
        when(straddleStrategy.getType()).thenReturn(StrategyType.STRADDLE);
        when(straddleStrategy.getStatus()).thenReturn(StrategyStatus.ACTIVE);
        when(straddleStrategy.getUnderlying()).thenReturn("NIFTY");
        when(straddleStrategy.getConfig()).thenReturn(config);
        when(straddleStrategy.getPositions()).thenReturn(List.of(pos));
        when(straddleStrategy.getLastEvaluationTime()).thenReturn(LocalDateTime.of(2025, 2, 7, 10, 30, 0));
        when(straddleStrategy.getEntryPremium()).thenReturn(BigDecimal.valueOf(205.75));

        when(strategyEngine.getStrategy("STR-001")).thenReturn(straddleStrategy);
        when(strategyLegJpaRepository.findByStrategyId("STR-001")).thenReturn(List.of());

        mockMvc.perform(get("/api/strategies/STR-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("STR-001"))
                .andExpect(jsonPath("$.data.name").value("Morning Straddle"))
                .andExpect(jsonPath("$.data.positions").isArray())
                .andExpect(jsonPath("$.data.positions.length()").value(1))
                .andExpect(jsonPath("$.data.entryPremium").value(205.75));
    }

    @Test
    @DisplayName("POST /api/strategies deploys a new strategy")
    void deployStrategyReturnsId() throws Exception {
        when(strategyEngine.deployStrategy(
                        eq(StrategyType.STRADDLE), eq("Test Straddle"), any(BaseStrategyConfig.class), eq(false)))
                .thenReturn("STR-002");

        String body = """
                {
                    "type": "STRADDLE",
                    "name": "Test Straddle",
                    "underlying": "NIFTY",
                    "autoArm": false
                }
                """;

        mockMvc.perform(post("/api/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.strategyId").value("STR-002"))
                .andExpect(jsonPath("$.data.message").value("Strategy deployed successfully"));
    }

    @Test
    @DisplayName("POST /api/strategies/{id}/arm arms the strategy")
    void armStrategyCalled() throws Exception {
        mockMvc.perform(post("/api/strategies/STR-001/arm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Strategy armed"))
                .andExpect(jsonPath("$.data.strategyId").value("STR-001"));

        verify(strategyEngine).armStrategy("STR-001");
    }

    @Test
    @DisplayName("POST /api/strategies/{id}/pause pauses the strategy")
    void pauseStrategyCalled() throws Exception {
        mockMvc.perform(post("/api/strategies/STR-001/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Strategy paused"))
                .andExpect(jsonPath("$.data.strategyId").value("STR-001"));

        verify(strategyEngine).pauseStrategy("STR-001");
    }

    @Test
    @DisplayName("POST /api/strategies/{id}/resume resumes a paused strategy")
    void resumeStrategyCalled() throws Exception {
        mockMvc.perform(post("/api/strategies/STR-001/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Strategy resumed"))
                .andExpect(jsonPath("$.data.strategyId").value("STR-001"));

        verify(strategyEngine).resumeStrategy("STR-001");
    }

    @Test
    @DisplayName("POST /api/strategies/{id}/close initiates strategy close")
    void closeStrategyCalled() throws Exception {
        mockMvc.perform(post("/api/strategies/STR-001/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Strategy close initiated"))
                .andExpect(jsonPath("$.data.strategyId").value("STR-001"));

        verify(strategyEngine).closeStrategy("STR-001");
    }

    @Test
    @DisplayName("POST /api/strategies/{id}/force-adjust triggers manual adjustment")
    void forceAdjustTriggersAdjustment() throws Exception {
        String body = """
                {
                    "actionType": "ROLL_UP",
                    "parameters": { "strikeOffset": 100 }
                }
                """;

        mockMvc.perform(post("/api/strategies/STR-001/force-adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Adjustment triggered"))
                .andExpect(jsonPath("$.data.strategyId").value("STR-001"));

        verify(strategyEngine).forceAdjustment(eq("STR-001"), any(AdjustmentAction.class));
    }

    @Test
    @DisplayName("POST /api/strategies/pause-all pauses all strategies")
    void pauseAllStrategiesCalled() throws Exception {
        mockMvc.perform(post("/api/strategies/pause-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("All strategies paused"));

        verify(strategyEngine).pauseAll();
    }

    @Test
    @DisplayName("DELETE /api/strategies/{id} undeploys a closed strategy")
    void undeployStrategyCalled() throws Exception {
        mockMvc.perform(delete("/api/strategies/STR-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Strategy undeployed"))
                .andExpect(jsonPath("$.data.strategyId").value("STR-001"));

        verify(strategyEngine).undeployStrategy("STR-001");
    }
}
