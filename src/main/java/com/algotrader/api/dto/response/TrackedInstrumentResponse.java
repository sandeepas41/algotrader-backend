package com.algotrader.api.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * DTO describing a tracked instrument in the indicator service.
 * Includes configuration and current state (bar count, indicator count).
 */
@Data
@Builder
public class TrackedInstrumentResponse {

    private Long instrumentToken;
    private String tradingSymbol;
    private long barDurationSeconds;
    private int barCount;
    private int indicatorCount;
}
