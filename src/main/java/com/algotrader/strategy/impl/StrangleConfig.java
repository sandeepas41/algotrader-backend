package com.algotrader.strategy.impl;

import com.algotrader.strategy.base.PositionalStrategyConfig;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Configuration for the short strangle strategy.
 *
 * <p>Extends {@link PositionalStrategyConfig} with strangle-specific parameters:
 * OTM offsets for both call and put sides, minimum IV for entry, and delta
 * threshold for shifting the strangle strikes.
 *
 * <p>A short strangle sells OTM CE + OTM PE. It profits from theta decay and
 * IV crush, with a wider profit zone than a straddle (since both strikes are OTM).
 * However, it collects less premium than a straddle.
 *
 * <p><b>Leg layout example (NIFTY at 22000, callOffset=200, putOffset=200):</b>
 * <pre>
 *   Sell PE 21800 | --- ATM 22000 --- | Sell CE 22200
 * </pre>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class StrangleConfig extends PositionalStrategyConfig {

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
     * Minimum ATM implied volatility required for entry.
     * Strangles benefit from high IV (more premium collected for OTM options).
     * Example: 14.0 means only enter when ATM IV >= 14%.
     */
    private BigDecimal minIV;

    /**
     * Delta threshold for shifting the strangle.
     * When the absolute net position delta exceeds this, the strangle is closed
     * and re-entered at new OTM strikes centered around the current ATM.
     * Example: 0.35 means shift when |delta| > 0.35.
     */
    private BigDecimal shiftDeltaThreshold;
}
