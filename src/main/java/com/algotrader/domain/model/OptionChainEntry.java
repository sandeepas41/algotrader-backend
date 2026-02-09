package com.algotrader.domain.model;

import java.math.BigDecimal;
import lombok.Data;

/**
 * A single row in the option chain — pairs call and put data at the same strike price.
 *
 * <p>Either call or put may be null if only one side has instruments (e.g., very deep
 * ITM/OTM strikes that only have one side listed). The UI renders this as an empty cell.
 */
@Data
public class OptionChainEntry {

    private final BigDecimal strike;
    private OptionData call;
    private OptionData put;

    public OptionChainEntry(BigDecimal strike) {
        this.strike = strike;
    }
}
