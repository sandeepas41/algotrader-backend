package com.algotrader.unit.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.HealthController;
import com.algotrader.broker.KiteAuthService;
import com.algotrader.broker.KiteMarketDataService;
import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.config.ApiResponseAdvice;
import com.algotrader.domain.enums.MarketPhase;
import com.algotrader.session.SessionHealthService;
import com.algotrader.session.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc tests for the HealthController.
 * Uses MockMvcBuilders.standaloneSetup since @WebMvcTest was removed in Spring Boot 4.0.
 */
@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    private MockMvc mockMvc;

    @Mock
    private KiteAuthService kiteAuthService;

    @Mock
    private KiteMarketDataService kiteMarketDataService;

    @Mock
    private SessionHealthService sessionHealthService;

    @Mock
    private TradingCalendarService tradingCalendarService;

    @BeforeEach
    void setUp() {
        HealthController controller = new HealthController(
                kiteAuthService, kiteMarketDataService, sessionHealthService, tradingCalendarService);
        // Set @Value field that would normally be injected by Spring
        ReflectionTestUtils.setField(controller, "tradingMode", "LIVE");

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiResponseAdvice())
                .build();
    }

    @Test
    @DisplayName("GET /api/health returns 200 with status UP (shallow)")
    void shallowHealthReturns200() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    @DisplayName("GET /api/health/detailed returns all subsystem statuses when UP")
    void detailedHealthAllUp() throws Exception {
        when(sessionHealthService.isSessionActive()).thenReturn(true);
        when(kiteAuthService.getCurrentUserId()).thenReturn("XY1234");
        when(kiteMarketDataService.isConnected()).thenReturn(true);
        when(kiteMarketDataService.isDegraded()).thenReturn(false);
        when(kiteMarketDataService.getSubscribedCount()).thenReturn(45);
        when(tradingCalendarService.getCurrentPhase()).thenReturn(MarketPhase.NORMAL);

        mockMvc.perform(get("/api/health/detailed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.subsystems.kiteSession.status").value("UP"))
                .andExpect(jsonPath("$.data.subsystems.tickFeed.status").value("UP"))
                .andExpect(jsonPath("$.data.marketPhase").value("NORMAL"));
    }

    @Test
    @DisplayName("GET /api/health/detailed shows DEGRADED when session is down")
    void detailedHealthDegradedWhenSessionDown() throws Exception {
        when(sessionHealthService.isSessionActive()).thenReturn(false);
        when(sessionHealthService.getState()).thenReturn(SessionState.EXPIRED);
        when(kiteMarketDataService.isConnected()).thenReturn(true);
        when(kiteMarketDataService.isDegraded()).thenReturn(false);
        when(kiteMarketDataService.getSubscribedCount()).thenReturn(30);
        when(tradingCalendarService.getCurrentPhase()).thenReturn(MarketPhase.NORMAL);

        mockMvc.perform(get("/api/health/detailed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.subsystems.kiteSession.status").value("DOWN"));
    }

    @Test
    @DisplayName("GET /api/health/detailed shows tick feed DEGRADED status")
    void detailedHealthTickFeedDegraded() throws Exception {
        when(sessionHealthService.isSessionActive()).thenReturn(true);
        when(kiteAuthService.getCurrentUserId()).thenReturn("XY1234");
        when(kiteMarketDataService.isConnected()).thenReturn(true);
        when(kiteMarketDataService.isDegraded()).thenReturn(true);
        when(kiteMarketDataService.getSubscribedCount()).thenReturn(10);
        when(tradingCalendarService.getCurrentPhase()).thenReturn(MarketPhase.NORMAL);

        mockMvc.perform(get("/api/health/detailed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.subsystems.tickFeed.status").value("DEGRADED"));
    }
}
