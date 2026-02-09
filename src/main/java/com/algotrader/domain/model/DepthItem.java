package com.algotrader.domain.model;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * A single level in the order book depth (bid or ask).
 * Kite provides 5 levels of depth for instruments subscribed in FULL mode.
 * Used by Tick's buyDepth/sellDepth lists.
 */
@Data
@Builder
public class DepthItem {

    /** Total quantity available at this price level. */
    private int quantity;

    private BigDecimal price;

    /** Number of orders aggregated at this price level. */
    private int orders;
}
