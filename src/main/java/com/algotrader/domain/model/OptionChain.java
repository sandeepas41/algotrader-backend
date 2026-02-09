package com.algotrader.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * Complete option chain for a single underlying and expiry.
 *
 * <p>Built by OptionChainService from instrument data + live Kite quotes + calculated
 * Greeks. Cached in Caffeine with a 60-second TTL per (underlying, expiry) key to
 * avoid excessive Kite API quote calls while keeping data reasonably fresh.
 *
 * <p>The chain is ordered by strike price (ascending). The ATM strike is the strike
 * nearest to the current spot price.
 */
@Data
@Builder
public class OptionChain {

    /** Root underlying, e.g., "NIFTY", "BANKNIFTY". */
    private String underlying;

    /** Current spot price of the underlying. */
    private BigDecimal spotPrice;

    /** Option expiry date. */
    private LocalDate expiry;

    /** Strike price nearest to spot. */
    private BigDecimal atmStrike;

    /** Chain entries ordered by strike (ascending). Each entry has call + put at same strike. */
    private List<OptionChainEntry> entries;
}
