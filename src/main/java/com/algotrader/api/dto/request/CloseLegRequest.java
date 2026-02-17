/**
 * Request to close a single strategy leg by placing an exit order for its linked position.
 * Supports both MARKET and LIMIT order types — LIMIT is needed for instruments
 * where Kite rejects MARKET orders (e.g., some illiquid options).
 */
package com.algotrader.api.dto.request;

import com.algotrader.domain.enums.OrderType;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class CloseLegRequest {
    /** ID of the leg to close. */
    private String legId;

    /** MARKET or LIMIT. Defaults to MARKET on the controller if null. */
    private OrderType orderType;

    /** Limit price — required when orderType is LIMIT. */
    private BigDecimal price;
}
