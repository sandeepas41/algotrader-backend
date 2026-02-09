package com.algotrader.api.controller;

import com.algotrader.api.dto.response.DashboardResponse;
import com.algotrader.broker.KiteAuthService;
import com.algotrader.broker.KiteMarketDataService;
import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.risk.AccountRiskChecker;
import com.algotrader.risk.KillSwitchService;
import com.algotrader.session.SessionHealthService;
import com.algotrader.strategy.base.BaseStrategy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provides the aggregated dashboard data endpoint.
 *
 * <p>GET /api/dashboard returns a single payload combining active strategy info,
 * P&L summary, system status, and risk indicators. This reduces the number of
 * API calls the frontend needs on dashboard page load.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final StrategyEngine strategyEngine;
    private final AccountRiskChecker accountRiskChecker;
    private final KillSwitchService killSwitchService;
    private final TradingCalendarService tradingCalendarService;
    private final KiteAuthService kiteAuthService;
    private final KiteMarketDataService kiteMarketDataService;
    private final SessionHealthService sessionHealthService;

    public DashboardController(
            StrategyEngine strategyEngine,
            AccountRiskChecker accountRiskChecker,
            KillSwitchService killSwitchService,
            TradingCalendarService tradingCalendarService,
            KiteAuthService kiteAuthService,
            KiteMarketDataService kiteMarketDataService,
            SessionHealthService sessionHealthService) {
        this.strategyEngine = strategyEngine;
        this.accountRiskChecker = accountRiskChecker;
        this.killSwitchService = killSwitchService;
        this.tradingCalendarService = tradingCalendarService;
        this.kiteAuthService = kiteAuthService;
        this.kiteMarketDataService = kiteMarketDataService;
        this.sessionHealthService = sessionHealthService;
    }

    /**
     * Returns aggregated dashboard data combining strategy, P&L, risk, and system status.
     */
    @GetMapping
    public ResponseEntity<DashboardResponse> getDashboard() {
        Map<String, List<String>> strategiesByStatus = buildStrategiesByStatus();

        DashboardResponse response = DashboardResponse.builder()
                .activeStrategyCount(strategyEngine.getActiveStrategyCount())
                .strategiesByStatus(strategiesByStatus)
                .dailyRealizedPnl(accountRiskChecker.getDailyRealisedPnl())
                .dailyLimitApproaching(accountRiskChecker.isDailyLimitApproaching())
                .dailyLimitBreached(accountRiskChecker.isDailyLimitBreached())
                .marketPhase(tradingCalendarService.getCurrentPhase().name())
                .killSwitchActive(killSwitchService.isActive())
                .brokerConnected(sessionHealthService.isSessionActive())
                .tickFeedConnected(kiteMarketDataService.isConnected())
                .subscribedInstrumentCount(kiteMarketDataService.getSubscribedCount())
                .openPositionCount(accountRiskChecker.getOpenPositionCount())
                .pendingOrderCount(accountRiskChecker.getPendingOrderCount())
                .build();

        return ResponseEntity.ok(response);
    }

    private Map<String, List<String>> buildStrategiesByStatus() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, BaseStrategy> entry :
                strategyEngine.getActiveStrategies().entrySet()) {
            BaseStrategy strategy = entry.getValue();
            String status = strategy.getStatus().name();
            result.computeIfAbsent(status, k -> new ArrayList<>()).add(strategy.getName());
        }
        return result;
    }
}
