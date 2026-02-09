package com.algotrader.strategy.impl;

import com.algotrader.strategy.base.PositionalStrategyConfig;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Configuration for the iron butterfly strategy.
 *
 * <p>Extends {@link PositionalStrategyConfig} with iron-butterfly-specific parameters:
 * wing width for protection, minimum IV for entry, and delta threshold for
 * rolling adjustments.
 *
 * <p>An iron butterfly sells ATM CE + ATM PE (straddle) and buys OTM CE + OTM PE
 * for protection. It is essentially a straddle with protective wings. Maximum profit
 * = net premium collected. Maximum loss = wingWidth - net premium.
 *
 * <p><b>Leg layout example (NIFTY at 22000, wingWidth=200):</b>
 * <pre>
 *   Buy PE 21800 | Sell PE 22000 (ATM) | Sell CE 22000 (ATM) | Buy CE 22200
 * </pre>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class IronButterflyConfig extends PositionalStrategyConfig {

    /**
     * Width between the ATM short strikes and the OTM long (protection) strikes.
     * Example: 200 means buy CE at ATM + 200, buy PE at ATM - 200.
     */
    private BigDecimal wingWidth;

    /**
     * Minimum ATM implied volatility required for entry.
     * Iron butterflies benefit from high IV (more premium collected at ATM).
     * Example: 14.0 means only enter when ATM IV >= 14%.
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
