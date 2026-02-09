package com.algotrader.strategy.impl;

import com.algotrader.strategy.base.PositionalStrategyConfig;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Configuration for the iron condor strategy.
 *
 * <p>Extends {@link PositionalStrategyConfig} with iron-condor-specific parameters:
 * OTM offsets for the short strikes, wing width for protection, minimum IV for entry,
 * and delta threshold for rolling adjustments.
 *
 * <p>An iron condor sells OTM CE + OTM PE and buys further OTM CE + PE for protection.
 * It profits from theta decay and IV crush in a range-bound market. The 4-leg structure
 * caps maximum loss at (wingWidth - net premium) per lot.
 *
 * <p><b>Strike layout example (NIFTY at 22000, callOffset=200, putOffset=200, wingWidth=100):</b>
 * <pre>
 *   Buy PE 21700 | Sell PE 21800 | --- ATM 22000 --- | Sell CE 22200 | Buy CE 22300
 * </pre>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class IronCondorConfig extends PositionalStrategyConfig {

    /**
     * Points above ATM for the short call strike.
     * Example: 200 means sell CE at ATM + 200.
     */
    private BigDecimal callOffset;

    /**
     * Points below ATM for the short put strike.
     * Example: 200 means sell PE at ATM - 200.
     */
    private BigDecimal putOffset;

    /**
     * Width between short and long (protection) strikes on each side.
     * Example: 100 means buy CE at sellCall + 100, buy PE at sellPut - 100.
     */
    private BigDecimal wingWidth;

    /**
     * Minimum ATM implied volatility required for entry.
     * Iron condors benefit from high IV (more premium collected).
     * Example: 15.0 means only enter when ATM IV >= 15%.
     */
    private BigDecimal minEntryIV;

    /**
     * Delta threshold for rolling adjustments.
     * When the absolute net position delta exceeds this, the threatened side is rolled.
     * Positive delta breach => roll call side up, negative => roll put side down.
     * Example: 0.30 means roll when |delta| > 0.30.
     */
    private BigDecimal deltaRollThreshold;
}
