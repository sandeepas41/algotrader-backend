package com.algotrader.api.dto.response;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * Summary response for the trade history page showing aggregated trade statistics.
 */
@Data
@Builder
public class TradeSummaryResponse {

    private int totalTrades;
    private int buyTrades;
    private int sellTrades;
    private BigDecimal totalVolume;
    private BigDecimal totalCharges;
    private BigDecimal netPnL;
}
