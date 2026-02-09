package com.algotrader.unit.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.SettingsController;
import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.config.ApiResponseAdvice;
import com.algotrader.domain.enums.MarketPhase;
import com.algotrader.observability.DecisionArchiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc tests for the SettingsController.
 * Uses MockMvcBuilders.standaloneSetup since @WebMvcTest was removed in Spring Boot 4.0.
 */
@ExtendWith(MockitoExtension.class)
class SettingsControllerTest {

    private MockMvc mockMvc;

    @Mock
    private TradingCalendarService tradingCalendarService;

    @Mock
    private DecisionArchiveService decisionArchiveService;

    @BeforeEach
    void setUp() {
        SettingsController controller = new SettingsController(tradingCalendarService, decisionArchiveService);
        // Set @Value field that would normally be injected by Spring
        ReflectionTestUtils.setField(controller, "tradingMode", "LIVE");

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiResponseAdvice())
                .build();
    }

    @Test
    @DisplayName("GET /api/settings returns current settings")
    void getSettingsReturnsCurrent() throws Exception {
        when(tradingCalendarService.getCurrentPhase()).thenReturn(MarketPhase.NORMAL);
        when(tradingCalendarService.isMarketOpen()).thenReturn(true);
        when(decisionArchiveService.isPersistDebug()).thenReturn(false);

        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.marketPhase").value("NORMAL"))
                .andExpect(jsonPath("$.data.marketOpen").value(true))
                .andExpect(jsonPath("$.data.persistDebugEnabled").value(false));
    }

    @Test
    @DisplayName("POST /api/settings/persist-debug enables debug persistence")
    void togglePersistDebugEnable() throws Exception {
        mockMvc.perform(post("/api/settings/persist-debug")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.persistDebugEnabled").value(true));

        verify(decisionArchiveService).setPersistDebug(true);
    }

    @Test
    @DisplayName("POST /api/settings/persist-debug disables debug persistence")
    void togglePersistDebugDisable() throws Exception {
        mockMvc.perform(post("/api/settings/persist-debug")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.persistDebugEnabled").value(false));

        verify(decisionArchiveService).setPersistDebug(false);
    }
}
