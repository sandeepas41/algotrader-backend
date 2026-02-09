package com.algotrader.unit.morph;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.MorphController;
import com.algotrader.config.ApiResponseAdvice;
import com.algotrader.domain.enums.MorphPlanStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.LegCloseStep;
import com.algotrader.domain.model.LegOpenStep;
import com.algotrader.domain.model.LegReassignStep;
import com.algotrader.domain.model.MorphExecutionPlan;
import com.algotrader.domain.model.MorphPlan;
import com.algotrader.domain.model.MorphRequest;
import com.algotrader.domain.model.MorphResult;
import com.algotrader.domain.model.StrategyCreateStep;
import com.algotrader.domain.model.StrategyLineage;
import com.algotrader.domain.model.StrategyLineageTree;
import com.algotrader.exception.GlobalExceptionHandler;
import com.algotrader.morph.MorphService;
import com.algotrader.morph.StrategyLineageService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for MorphController: REST endpoint behavior for morph operations,
 * plan queries, and lineage queries.
 */
@ExtendWith(MockitoExtension.class)
class MorphControllerTest {

    @Mock
    private MorphService morphService;

    @Mock
    private StrategyLineageService strategyLineageService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MorphController morphController = new MorphController(morphService, strategyLineageService);
        mockMvc = MockMvcBuilders.standaloneSetup(morphController)
                .setControllerAdvice(new ApiResponseAdvice(), new GlobalExceptionHandler())
                .build();
    }

    // ========================
    // MORPH
    // ========================

    @Nested
    @DisplayName("POST /api/morph")
    class Morph {

        @Test
        @DisplayName("should execute morph and return result")
        void morphSuccess() throws Exception {
            MorphResult result = MorphResult.builder()
                    .sourceStrategyId("IC-001")
                    .newStrategyIds(List.of("BPS-002", "STR-003"))
                    .legsClosedCount(2)
                    .legsReassignedCount(2)
                    .legsOpenedCount(2)
                    .success(true)
                    .morphPlanId(1L)
                    .build();

            when(morphService.morph(any(MorphRequest.class))).thenReturn(result);

            mockMvc.perform(post("/api/morph")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                    {
                        "sourceStrategyId": "IC-001",
                        "targets": [
                            {
                                "strategyType": "BULL_PUT_SPREAD",
                                "retainedLegs": ["SELL_PE", "BUY_PE"]
                            },
                            {
                                "strategyType": "STRADDLE",
                                "newLegs": [
                                    {"strike": 24500, "optionType": "CE", "side": "SELL", "lots": 1},
                                    {"strike": 24500, "optionType": "PE", "side": "SELL", "lots": 1}
                                ]
                            }
                        ],
                        "copyEntryPrices": true,
                        "reason": "NIFTY broke above IC short call"
                    }
                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sourceStrategyId").value("IC-001"))
                    .andExpect(jsonPath("$.data.newStrategyIds").isArray())
                    .andExpect(jsonPath("$.data.newStrategyIds[0]").value("BPS-002"))
                    .andExpect(jsonPath("$.data.success").value(true))
                    .andExpect(jsonPath("$.data.legsClosedCount").value(2));
        }
    }

    // ========================
    // PREVIEW
    // ========================

    @Nested
    @DisplayName("POST /api/morph/preview")
    class MorphPreview {

        @Test
        @DisplayName("should return preview without executing")
        void previewPlan() throws Exception {
            MorphExecutionPlan plan = MorphExecutionPlan.builder()
                    .sourceStrategyId("IC-001")
                    .sourceType(StrategyType.IRON_CONDOR)
                    .legsToClose(List.of(
                            LegCloseStep.builder().positionId("P1").build(),
                            LegCloseStep.builder().positionId("P2").build()))
                    .legsToReassign(
                            List.of(LegReassignStep.builder().positionId("P3").build()))
                    .legsToOpen(List.of(
                            LegOpenStep.builder().targetStrategyId("NEW-1").build()))
                    .strategiesToCreate(List.of(StrategyCreateStep.builder()
                            .strategyType(StrategyType.STRADDLE)
                            .build()))
                    .build();

            when(morphService.preview(any(MorphRequest.class))).thenReturn(plan);

            mockMvc.perform(post("/api/morph/preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                    {
                        "sourceStrategyId": "IC-001",
                        "targets": [{"strategyType": "STRADDLE"}]
                    }
                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.sourceStrategyId").value("IC-001"))
                    .andExpect(jsonPath("$.data.legsToCloseCount").value(2))
                    .andExpect(jsonPath("$.data.legsToReassignCount").value(1))
                    .andExpect(jsonPath("$.data.legsToOpenCount").value(1))
                    .andExpect(jsonPath("$.data.strategiesToCreateCount").value(1));
        }
    }

    // ========================
    // PLANS
    // ========================

    @Nested
    @DisplayName("GET /api/morph/plans")
    class Plans {

        @Test
        @DisplayName("should return all morph plans")
        void getAllPlans() throws Exception {
            MorphPlan plan = MorphPlan.builder()
                    .id(1L)
                    .sourceStrategyId("IC-001")
                    .sourceStrategyType(StrategyType.IRON_CONDOR)
                    .status(MorphPlanStatus.COMPLETED)
                    .reason("Test")
                    .createdAt(LocalDateTime.of(2026, 1, 15, 10, 30))
                    .build();

            when(morphService.getAllPlans()).thenReturn(List.of(plan));

            mockMvc.perform(get("/api/morph/plans"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].sourceStrategyId").value("IC-001"))
                    .andExpect(jsonPath("$.data[0].status").value("COMPLETED"));
        }

        @Test
        @DisplayName("should return specific morph plan by ID")
        void getPlanById() throws Exception {
            MorphPlan plan = MorphPlan.builder()
                    .id(42L)
                    .sourceStrategyId("IC-001")
                    .status(MorphPlanStatus.PLANNED)
                    .build();

            when(morphService.getPlan(42L)).thenReturn(plan);

            mockMvc.perform(get("/api/morph/plans/42"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(42))
                    .andExpect(jsonPath("$.data.status").value("PLANNED"));
        }
    }

    // ========================
    // LINEAGE
    // ========================

    @Nested
    @DisplayName("Lineage endpoints")
    class Lineage {

        @Test
        @DisplayName("should return lineage tree")
        void lineageTree() throws Exception {
            StrategyLineage parent = StrategyLineage.builder()
                    .id(1L)
                    .parentStrategyId("ORIG-001")
                    .childStrategyId("IC-001")
                    .parentStrategyType(StrategyType.STRADDLE)
                    .childStrategyType(StrategyType.IRON_CONDOR)
                    .morphReason("Converted straddle to IC")
                    .parentPnlAtMorph(new BigDecimal("-5000"))
                    .morphedAt(LocalDateTime.of(2026, 1, 10, 9, 15))
                    .build();

            StrategyLineageTree tree = StrategyLineageTree.builder()
                    .strategyId("IC-001")
                    .ancestors(List.of(parent))
                    .descendants(List.of())
                    .build();

            when(strategyLineageService.getLineageTree("IC-001")).thenReturn(tree);

            mockMvc.perform(get("/api/morph/lineage/IC-001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.strategyId").value("IC-001"))
                    .andExpect(jsonPath("$.data.ancestors").isArray())
                    .andExpect(jsonPath("$.data.ancestors[0].parentStrategyId").value("ORIG-001"))
                    .andExpect(jsonPath("$.data.descendants").isEmpty());
        }

        @Test
        @DisplayName("should return cumulative P&L")
        void cumulativePnl() throws Exception {
            when(strategyLineageService.getCumulativePnl("IC-001")).thenReturn(new BigDecimal("12500.50"));

            mockMvc.perform(get("/api/morph/lineage/IC-001/cumulative-pnl"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(12500.50));
        }
    }
}
