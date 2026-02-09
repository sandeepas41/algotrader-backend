package com.algotrader.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * DTO for a single OHLCV bar returned by the indicator REST API.
 * Used by the frontend's candlestick chart component.
 */
@Data
@Builder
public class BarDataResponse {

    private LocalDateTime timestamp;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private long volume;
}
