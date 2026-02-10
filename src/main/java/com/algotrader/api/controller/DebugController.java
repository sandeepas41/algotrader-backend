package com.algotrader.api.controller;

import com.algotrader.api.websocket.StompTickSubscriptionInterceptor;
import com.algotrader.broker.InstrumentSubscriptionManager;
import com.algotrader.broker.KiteMarketDataService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Debug endpoints for inspecting real-time system state.
 *
 * <p>Exposes internal subscription state, Kite connection status, and STOMP
 * session mappings. Intended for developer debugging — not for production
 * dashboards. All endpoints are read-only.
 */
@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private final InstrumentSubscriptionManager instrumentSubscriptionManager;
    private final KiteMarketDataService kiteMarketDataService;
    private final StompTickSubscriptionInterceptor stompTickSubscriptionInterceptor;

    public DebugController(
            InstrumentSubscriptionManager instrumentSubscriptionManager,
            KiteMarketDataService kiteMarketDataService,
            StompTickSubscriptionInterceptor stompTickSubscriptionInterceptor) {
        this.instrumentSubscriptionManager = instrumentSubscriptionManager;
        this.kiteMarketDataService = kiteMarketDataService;
        this.stompTickSubscriptionInterceptor = stompTickSubscriptionInterceptor;
    }

    /**
     * Returns current subscription state across all layers:
     * - Kite ticker connection status and subscribed tokens
     * - InstrumentSubscriptionManager active tokens and count
     * - STOMP session → token mappings from the interceptor
     */
    @GetMapping("/subscriptions")
    public Map<String, Object> getSubscriptions() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Kite ticker state
        Map<String, Object> kite = new LinkedHashMap<>();
        kite.put("connected", kiteMarketDataService.isConnected());
        kite.put("degraded", kiteMarketDataService.isDegraded());
        kite.put("subscribedCount", kiteMarketDataService.getSubscribedCount());
        kite.put("subscribedTokens", kiteMarketDataService.getSubscribedTokens());
        result.put("kite", kite);

        // InstrumentSubscriptionManager state
        Map<String, Object> manager = new LinkedHashMap<>();
        Set<Long> activeTokens = instrumentSubscriptionManager.getActiveTokens();
        manager.put("activeCount", instrumentSubscriptionManager.getActiveCount());
        manager.put("activeTokens", activeTokens);
        result.put("subscriptionManager", manager);

        // STOMP session mappings
        Map<String, Map<String, Long>> sessionSubs = stompTickSubscriptionInterceptor.getSessionSubscriptions();
        Map<String, Object> stomp = new LinkedHashMap<>();
        stomp.put("activeSessions", sessionSubs.size());

        // Flatten to sessionId → list of tokens (drop subscriptionId detail)
        Map<String, List<Long>> sessionTokens = new HashMap<>();
        for (var entry : sessionSubs.entrySet()) {
            sessionTokens.put(entry.getKey(), List.copyOf(entry.getValue().values()));
        }
        stomp.put("sessions", sessionTokens);
        result.put("stomp", stomp);

        return result;
    }
}
