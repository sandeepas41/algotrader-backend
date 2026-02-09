package com.algotrader.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * Option Greeks calculated via Black-Scholes. Kite does NOT provide Greeks —
 * we compute them locally using spot price, strike, expiry, and option LTP.
 *
 * <p>IV is solved first using Newton-Raphson (with bisection fallback), then
 * the remaining Greeks are derived analytically. Greeks are recalculated on every
 * tick for active positions and cached with 5s TTL for option chain display.
 *
 * <p>When the IV solver fails to converge, the UNAVAILABLE sentinel is returned
 * instead of potentially incorrect values. Check {@link #isAvailable()} before using.
 */
@Data
@Builder
public class Greeks {

    /** Price sensitivity to underlying movement. Range: -1 (deep ITM put) to +1 (deep ITM call). */
    private BigDecimal delta;

    /** Rate of change of delta. Highest for ATM options. */
    private BigDecimal gamma;

    /** Time decay per day in rupees. Negative for long options (value erodes). */
    private BigDecimal theta;

    /** Sensitivity to 1% change in implied volatility. */
    private BigDecimal vega;

    /** Sensitivity to interest rate changes. Typically small for short-dated options. */
    private BigDecimal rho;

    /** Implied volatility as a decimal (e.g., 0.16 = 16%). Solved via Newton-Raphson. */
    private BigDecimal iv;

    private LocalDateTime calculatedAt;

    /**
     * Sentinel for when IV solver doesn't converge (e.g., deep OTM options with
     * near-zero premium, or stale/invalid price data). IV is set to -1 to make
     * it clearly distinguishable from valid values.
     */
    public static final Greeks UNAVAILABLE = Greeks.builder()
            .delta(BigDecimal.ZERO)
            .gamma(BigDecimal.ZERO)
            .theta(BigDecimal.ZERO)
            .vega(BigDecimal.ZERO)
            .rho(BigDecimal.ZERO)
            .iv(BigDecimal.valueOf(-1))
            .calculatedAt(LocalDateTime.MIN)
            .build();

    public boolean isAvailable() {
        return this != UNAVAILABLE && iv != null && iv.compareTo(BigDecimal.ZERO) > 0;
    }
}
