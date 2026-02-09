package com.algotrader.api.dto.response;

import java.util.Map;
import lombok.Builder;
import lombok.Getter;

/**
 * Detailed health check response for the monitoring dashboard.
 *
 * <p>Returned by GET /api/health/detailed. Includes subsystem statuses for
 * H2, Redis, Kite session, tick feed, and trading mode. The frontend's
 * System Status panel uses this to display connection indicators.
 */
@Getter
@Builder
public class HealthDetailedResponse {

    /** Overall health status: "UP", "DEGRADED", or "DOWN". */
    private final String status;

    /** Individual subsystem statuses. */
    private final Map<String, SubsystemHealth> subsystems;

    /** Current trading mode (LIVE, PAPER, HYBRID). */
    private final String tradingMode;

    /** Current market phase. */
    private final String marketPhase;

    @Getter
    @Builder
    public static class SubsystemHealth {
        private final String status;
        private final String message;
    }
}
