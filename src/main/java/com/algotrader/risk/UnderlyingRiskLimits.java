package com.algotrader.risk;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * Per-underlying risk limits to prevent over-concentration in a single instrument.
 *
 * <p>These limits are checked IN ADDITION to global {@link RiskLimits}. For example,
 * even if global maxLotsPerPosition is 10, the per-underlying maxLots might cap
 * total NIFTY exposure at 5 lots across all strategies.
 *
 * <p>Stored in a ConcurrentHashMap keyed by underlying symbol in RiskManager.
 * Can be configured via Risk API per underlying.
 */
@Data
@Builder
public class UnderlyingRiskLimits {

    /** Underlying symbol (e.g., "NIFTY", "BANKNIFTY"). */
    private String underlying;

    /** Maximum notional exposure for this underlying (INR). */
    private BigDecimal maxExposure;

    /** Maximum total lots across all strategies for this underlying. */
    private Integer maxLots;

    /** Maximum concurrent strategies on this underlying. */
    private Integer maxStrategies;

    /** Maximum daily loss for this underlying (INR). */
    private BigDecimal maxLossPerUnderlying;
}
