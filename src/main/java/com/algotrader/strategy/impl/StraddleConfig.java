package com.algotrader.strategy.impl;

import com.algotrader.strategy.base.PositionalStrategyConfig;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Configuration for the short straddle strategy.
 *
 * <p>Extends {@link PositionalStrategyConfig} with straddle-specific parameters:
 * minimum IV for entry and delta threshold for shifting the straddle strike.
 *
 * <p>A short straddle sells ATM CE + ATM PE. It profits from theta decay and
 * IV crush, but requires active management when the underlying moves. The
 * shiftDeltaThreshold controls when the straddle is closed and re-entered at
 * the new ATM strike.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class StraddleConfig extends PositionalStrategyConfig {

    /**
     * Minimum ATM implied volatility required for entry.
     * Straddles benefit from high IV; entering at low IV risks insufficient premium.
     * Example: 12.0 means only enter when ATM IV >= 12%.
     */
    private BigDecimal minIV;

    /**
     * Delta threshold for shifting the straddle.
     * When the absolute net position delta exceeds this, the straddle is closed
     * and re-entered at the new ATM strike.
     * Example: 0.35 means shift when |delta| > 0.35.
     */
    private BigDecimal shiftDeltaThreshold;
}
