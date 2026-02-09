package com.algotrader.unit.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.RiskController;
import com.algotrader.config.ApiResponseAdvice;
import com.algotrader.risk.AccountRiskChecker;
import com.algotrader.risk.KillSwitchResult;
import com.algotrader.risk.KillSwitchService;
import com.algotrader.risk.RiskLimitPersistenceService;
import com.algotrader.risk.RiskLimits;
import java.math.BigDecimal;
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
 * Standalone MockMvc tests for the RiskController.
 * Uses MockMvcBuilders.standaloneSetup since @WebMvcTest was removed in Spring Boot 4.0.
 */
@ExtendWith(MockitoExtension.class)
class RiskControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AccountRiskChecker accountRiskChecker;

    @Mock
    private KillSwitchService killSwitchService;

    @Mock
    private RiskLimitPersistenceService riskLimitPersistenceService;

    private RiskLimits riskLimits;

    @BeforeEach
    void setUp() {
        riskLimits = RiskLimits.builder()
                .dailyLossLimit(BigDecimal.valueOf(50000))
                .maxLossPerPosition(BigDecimal.valueOf(10000))
                .maxOpenPositions(10)
                .maxOpenOrders(20)
                .maxActiveStrategies(5)
                .build();

        RiskController controller =
                new RiskController(accountRiskChecker, killSwitchService, riskLimits, riskLimitPersistenceService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiResponseAdvice())
                .build();
    }

    @Test
    @DisplayName("GET /api/risk/status returns current risk status")
    void getRiskStatusReturnsAllFields() throws Exception {
        when(accountRiskChecker.getDailyRealisedPnl()).thenReturn(BigDecimal.valueOf(-15000));
        when(accountRiskChecker.isDailyLimitApproaching()).thenReturn(true);
        when(accountRiskChecker.isDailyLimitBreached()).thenReturn(false);
        when(accountRiskChecker.getOpenPositionCount()).thenReturn(4);
        when(accountRiskChecker.getPendingOrderCount()).thenReturn(2);
        when(killSwitchService.isActive()).thenReturn(false);

        mockMvc.perform(get("/api/risk/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailyRealizedPnl").value(-15000))
                .andExpect(jsonPath("$.data.dailyLimitApproaching").value(true))
                .andExpect(jsonPath("$.data.dailyLimitBreached").value(false))
                .andExpect(jsonPath("$.data.openPositionCount").value(4))
                .andExpect(jsonPath("$.data.pendingOrderCount").value(2))
                .andExpect(jsonPath("$.data.killSwitchActive").value(false));
    }

    @Test
    @DisplayName("GET /api/risk/limits returns current risk limits")
    void getRiskLimitsReturnsConfig() throws Exception {
        mockMvc.perform(get("/api/risk/limits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailyLossLimit").value(50000))
                .andExpect(jsonPath("$.data.maxLossPerPosition").value(10000))
                .andExpect(jsonPath("$.data.maxOpenPositions").value(10))
                .andExpect(jsonPath("$.data.maxOpenOrders").value(20))
                .andExpect(jsonPath("$.data.maxActiveStrategies").value(5));
    }

    @Test
    @DisplayName("PUT /api/risk/limits updates daily loss limit")
    void updateRiskLimitsUpdatesDailyLossLimit() throws Exception {
        String body = """
                { "dailyLossLimit": 75000 }
                """;

        mockMvc.perform(put("/api/risk/limits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailyLossLimit").value(75000));

        verify(riskLimitPersistenceService).recordChange("GLOBAL", "dailyLossLimit", "50000", "75000", "API", null);
    }

    @Test
    @DisplayName("PUT /api/risk/limits updates max open positions")
    void updateRiskLimitsUpdatesMaxOpenPositions() throws Exception {
        String body = """
                { "maxOpenPositions": 15 }
                """;

        mockMvc.perform(put("/api/risk/limits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxOpenPositions").value(15));

        verify(riskLimitPersistenceService).recordChange("GLOBAL", "maxOpenPositions", "10", "15", "API", null);
    }

    @Test
    @DisplayName("POST /api/risk/kill-switch activates with CONFIRM text")
    void activateKillSwitchWithConfirm() throws Exception {
        KillSwitchResult result = KillSwitchResult.builder()
                .success(true)
                .strategiesPaused(3)
                .ordersCancelled(5)
                .positionsClosed(6)
                .reason("Manual activation via API")
                .build();
        when(killSwitchService.activate("Emergency shutdown")).thenReturn(result);

        String body = """
                { "confirm": "CONFIRM", "reason": "Emergency shutdown" }
                """;

        mockMvc.perform(post("/api/risk/kill-switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.strategiesPaused").value(3))
                .andExpect(jsonPath("$.data.ordersCancelled").value(5))
                .andExpect(jsonPath("$.data.positionsClosed").value(6));
    }

    @Test
    @DisplayName("POST /api/risk/kill-switch rejects without CONFIRM text")
    void activateKillSwitchRejectsWithoutConfirm() throws Exception {
        String body = """
                { "confirm": "yes please" }
                """;

        mockMvc.perform(post("/api/risk/kill-switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.error").exists());

        verify(killSwitchService, never()).activate(anyString());
    }

    @Test
    @DisplayName("POST /api/risk/kill-switch/deactivate deactivates the kill switch")
    void deactivateKillSwitch() throws Exception {
        mockMvc.perform(post("/api/risk/kill-switch/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Kill switch deactivated"));

        verify(killSwitchService).deactivate();
    }

    @Test
    @DisplayName("POST /api/risk/pause-all pauses all strategies")
    void pauseAllStrategies() throws Exception {
        when(killSwitchService.pauseAllStrategies()).thenReturn(4);

        mockMvc.perform(post("/api/risk/pause-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("All strategies paused"))
                .andExpect(jsonPath("$.data.strategiesPaused").value(4));
    }
}
