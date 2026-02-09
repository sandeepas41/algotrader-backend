package com.algotrader.strategy.impl;

import com.algotrader.strategy.base.PositionalStrategyConfig;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Configuration for the broken wing butterfly strategy.
 *
 * <p>Extends {@link PositionalStrategyConfig} with asymmetric butterfly parameters:
 * independent wing widths for call and put sides, minimum IV for entry, and delta
 * threshold for adjustments.
 *
 * <p>A broken wing butterfly is an iron butterfly with unequal wings. By making one
 * wing wider than the other, the trader can:
 * <ul>
 *   <li>Reduce or eliminate the debit on one side (enter for a credit or near-zero cost)</li>
 *   <li>Accept higher risk on the wider-wing side in exchange for the credit</li>
 *   <li>Express a directional bias while still profiting from theta decay</li>
 * </ul>
 *
 * <p><b>Leg layout example (NIFTY at 22000, callWingWidth=100, putWingWidth=300):</b>
 * <pre>
 *   Buy PE 21700 | Sell PE 22000 (ATM) | Sell CE 22000 (ATM) | Buy CE 22100
 * </pre>
 * The put wing is wider (300 vs 100), meaning more risk on the downside but the
 * strategy can potentially be entered for a net credit.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class BrokenWingButterflyConfig extends PositionalStrategyConfig {

    /**
     * Width between the ATM short call and the OTM long call (protection).
     * Example: 100 means buy CE at ATM + 100.
     */
    private BigDecimal callWingWidth;

    /**
     * Width between the ATM short put and the OTM long put (protection).
     * Example: 300 means buy PE at ATM - 300.
     * Making this larger than callWingWidth creates downside risk bias.
     */
    private BigDecimal putWingWidth;

    /**
     * Minimum ATM implied volatility required for entry.
     * Example: 14.0 means only enter when ATM IV >= 14%.
     */
    private BigDecimal minEntryIV;

    /**
     * Delta threshold for rolling adjustments.
     * When the absolute net position delta exceeds this, the threatened side is rolled.
     * Example: 0.30 means roll when |delta| > 0.30.
     */
    private BigDecimal deltaRollThreshold;
}
