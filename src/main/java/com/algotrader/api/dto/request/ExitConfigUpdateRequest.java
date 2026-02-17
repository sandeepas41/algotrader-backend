package com.algotrader.api.dto.request;

import java.math.BigDecimal;
import lombok.Data;

/**
 * Request to update the absolute PnL exit config of a strategy at runtime.
 *
 * <p>Both fields are optional. Only non-null fields are updated on the config.
 * To disable a threshold, send the value as 0 (or update again without it).
 */
@Data
public class ExitConfigUpdateRequest {

    /** Absolute profit target in Rs. Null = no change. */
    private BigDecimal targetPnl;

    /** Absolute stop-loss in Rs (should be negative). Null = no change. */
    private BigDecimal stopLossPnl;
}
