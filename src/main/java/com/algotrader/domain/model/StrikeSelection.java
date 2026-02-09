package com.algotrader.domain.model;

import com.algotrader.domain.enums.StrikeType;
import lombok.Builder;
import lombok.Data;

/**
 * Defines how a strategy leg's strike price is selected relative to the current spot.
 *
 * <p>Examples: ATM with offset 0 = at-the-money, OTM with offset 2 = 2 strikes
 * out-of-the-money, FIXED with a specific strike value. Stored as JSON in
 * the strategy_legs.strike_selection column.
 */
@Data
@Builder
public class StrikeSelection {

    private StrikeType type;

    /** Number of strikes away from ATM. Positive = further OTM, negative = further ITM. */
    private int offset;

    /** Absolute strike price. Only used when type = FIXED. */
    private java.math.BigDecimal fixedStrike;
}
