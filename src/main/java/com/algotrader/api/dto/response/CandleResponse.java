package com.algotrader.api.dto.response;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * DTO for a single OHLCV candle returned by the candle REST API.
 *
 * <p>Used by the frontend's chart components to render candlestick or bar charts.
 * The timestamp is epoch milliseconds (not ISO string) for direct use in charting
 * libraries that expect numeric x-axis values.
 */
@Data
@Builder
public class CandleResponse {

    /** Epoch millisecond of the candle bucket start. */
    private long timestamp;

    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;

    /** Total traded volume in this candle interval. */
    private long volume;
}
