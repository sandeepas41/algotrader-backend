/**
 * Request to roll a strategy leg to a different strike price.
 * Atomically closes the current leg position and opens a new one at the target strike.
 * Both close and open legs support MARKET/LIMIT order types independently.
 */
package com.algotrader.api.dto.request;

import com.algotrader.domain.enums.OrderType;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class RollLegRequest {
    /** ID of the leg to roll. */
    private String legId;

    /** Target strike price for the new position. */
    private BigDecimal newStrike;

    /** Order type for closing the current position. Defaults to MARKET. */
    private OrderType closeOrderType;

    /** Limit price for close order — required when closeOrderType is LIMIT. */
    private BigDecimal closePrice;

    /** Order type for opening the new position. Defaults to MARKET. */
    private OrderType openOrderType;

    /** Limit price for open order — required when openOrderType is LIMIT. */
    private BigDecimal openPrice;
}
