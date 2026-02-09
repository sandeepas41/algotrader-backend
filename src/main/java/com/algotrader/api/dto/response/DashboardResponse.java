package com.algotrader.api.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

/**
 * Aggregated dashboard data returned by GET /api/dashboard.
 *
 * <p>Combines data from multiple services into a single response to
 * minimize frontend API calls on page load. Includes active strategy
 * count, P&L summary, recent decisions, portfolio Greeks, and system status.
 */
@Getter
@Builder
public class DashboardResponse {

    /** Number of strategies currently in ACTIVE, ARMED, or PAUSED state. */
    private final int activeStrategyCount;

    /** Strategy names by status (e.g., {"ACTIVE": ["Nifty IC", "BNF Straddle"], "PAUSED": ["Scalp"]}). */
    private final Map<String, List<String>> strategiesByStatus;

    /** Current day's realized P&L across all positions. */
    private final BigDecimal dailyRealizedPnl;

    /** Whether the daily loss limit is approaching (80% threshold). */
    private final boolean dailyLimitApproaching;

    /** Whether the daily loss limit has been breached. */
    private final boolean dailyLimitBreached;

    /** Current market phase (PRE_OPEN, NORMAL, CLOSED, etc.). */
    private final String marketPhase;

    /** Whether the kill switch is currently active. */
    private final boolean killSwitchActive;

    /** Whether the Kite session is authenticated and active. */
    private final boolean brokerConnected;

    /** Kite WebSocket connection status. */
    private final boolean tickFeedConnected;

    /** Number of instruments subscribed for tick data. */
    private final int subscribedInstrumentCount;

    /** Open position count across all strategies. */
    private final int openPositionCount;

    /** Pending order count. */
    private final int pendingOrderCount;
}
