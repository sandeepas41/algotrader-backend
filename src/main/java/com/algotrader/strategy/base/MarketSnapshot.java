package com.algotrader.strategy.base;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * Point-in-time snapshot of market conditions for a specific underlying.
 *
 * <p>Passed to strategy evaluation methods (shouldEnter, shouldExit, adjust).
 * Contains the spot price, ATM implied volatility, and timestamp. Strategy
 * implementations use this to make entry/exit/adjustment decisions without
 * directly querying market data services.
 *
 * <p>Built by the StrategyEngine from the latest tick data before each evaluation cycle.
 */
@Data
@Builder
public class MarketSnapshot {

    /** Underlying spot price (e.g., NIFTY index value). */
    private BigDecimal spotPrice;

    /** ATM implied volatility for the underlying. */
    private BigDecimal atmIV;

    /** When this snapshot was captured. */
    private LocalDateTime timestamp;
}
