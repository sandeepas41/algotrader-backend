package com.algotrader.api.dto.response;

import lombok.Builder;
import lombok.Getter;

/**
 * Response DTO for the GET /api/settings endpoint.
 *
 * <p>Returns current system configuration values that can be toggled
 * at runtime (trading mode, market status override, persist-debug).
 */
@Getter
@Builder
public class SettingsResponse {

    /** Current trading mode: LIVE, PAPER, or HYBRID. */
    private final String tradingMode;

    /** Current market phase as reported by TradingCalendarService. */
    private final String marketPhase;

    /** Whether the market is currently open for trading. */
    private final boolean marketOpen;

    /** Whether debug-level decisions are being persisted to H2. */
    private final boolean persistDebugEnabled;
}
