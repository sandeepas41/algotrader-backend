package com.algotrader.unit.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.DashboardController;
import com.algotrader.broker.KiteAuthService;
import com.algotrader.broker.KiteMarketDataService;
import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.config.ApiResponseAdvice;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.MarketPhase;
import com.algotrader.risk.AccountRiskChecker;
import com.algotrader.risk.KillSwitchService;
import com.algotrader.session.SessionHealthService;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc tests for the DashboardController.
 * Uses MockMvcBuilders.standaloneSetup since @WebMvcTest was removed in Spring Boot 4.0.
 */
@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    private MockMvc mockMvc;

    @Mock
    private StrategyEngine strategyEngine;

    @Mock
    private AccountRiskChecker accountRiskChecker;

    @Mock
    private KillSwitchService killSwitchService;

    @Mock
    private TradingCalendarService tradingCalendarService;

    @Mock
    private KiteAuthService kiteAuthService;

    @Mock
    private KiteMarketDataService kiteMarketDataService;

    @Mock
    private SessionHealthService sessionHealthService;

    @BeforeEach
    void setUp() {
        DashboardController controller = new DashboardController(
                strategyEngine,
                accountRiskChecker,
                killSwitchService,
                tradingCalendarService,
                kiteAuthService,
                kiteMarketDataService,
                sessionHealthService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiResponseAdvice())
                .build();
    }

    @Test
    @DisplayName("GET /api/dashboard returns aggregated dashboard data")
    void getDashboardReturnsAggregatedData() throws Exception {
        when(strategyEngine.getActiveStrategyCount()).thenReturn(3);
        when(strategyEngine.getActiveStrategies()).thenReturn(Map.of());
        when(accountRiskChecker.getDailyRealisedPnl()).thenReturn(BigDecimal.valueOf(-12500));
        when(accountRiskChecker.isDailyLimitApproaching()).thenReturn(true);
        when(accountRiskChecker.isDailyLimitBreached()).thenReturn(false);
        when(accountRiskChecker.getOpenPositionCount()).thenReturn(6);
        when(accountRiskChecker.getPendingOrderCount()).thenReturn(2);
        when(tradingCalendarService.getCurrentPhase()).thenReturn(MarketPhase.NORMAL);
        when(killSwitchService.isActive()).thenReturn(false);
        when(sessionHealthService.isSessionActive()).thenReturn(true);
        when(kiteMarketDataService.isConnected()).thenReturn(true);
        when(kiteMarketDataService.getSubscribedCount()).thenReturn(45);

        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeStrategyCount").value(3))
                .andExpect(jsonPath("$.data.dailyRealizedPnl").value(-12500))
                .andExpect(jsonPath("$.data.dailyLimitApproaching").value(true))
                .andExpect(jsonPath("$.data.dailyLimitBreached").value(false))
                .andExpect(jsonPath("$.data.marketPhase").value("NORMAL"))
                .andExpect(jsonPath("$.data.killSwitchActive").value(false))
                .andExpect(jsonPath("$.data.brokerConnected").value(true))
                .andExpect(jsonPath("$.data.tickFeedConnected").value(true))
                .andExpect(jsonPath("$.data.subscribedInstrumentCount").value(45))
                .andExpect(jsonPath("$.data.openPositionCount").value(6))
                .andExpect(jsonPath("$.data.pendingOrderCount").value(2));
    }

    @Test
    @DisplayName("GET /api/dashboard shows kill switch active when engaged")
    void getDashboardShowsKillSwitchActive() throws Exception {
        when(strategyEngine.getActiveStrategyCount()).thenReturn(0);
        when(strategyEngine.getActiveStrategies()).thenReturn(Map.of());
        when(accountRiskChecker.getDailyRealisedPnl()).thenReturn(BigDecimal.valueOf(-50000));
        when(accountRiskChecker.isDailyLimitApproaching()).thenReturn(false);
        when(accountRiskChecker.isDailyLimitBreached()).thenReturn(true);
        when(accountRiskChecker.getOpenPositionCount()).thenReturn(0);
        when(accountRiskChecker.getPendingOrderCount()).thenReturn(0);
        when(tradingCalendarService.getCurrentPhase()).thenReturn(MarketPhase.NORMAL);
        when(killSwitchService.isActive()).thenReturn(true);
        when(sessionHealthService.isSessionActive()).thenReturn(true);
        when(kiteMarketDataService.isConnected()).thenReturn(true);
        when(kiteMarketDataService.getSubscribedCount()).thenReturn(0);

        mockMvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.killSwitchActive").value(true))
                .andExpect(jsonPath("$.data.dailyLimitBreached").value(true));
    }
}
