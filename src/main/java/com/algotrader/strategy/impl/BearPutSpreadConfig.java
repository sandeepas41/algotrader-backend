package com.algotrader.strategy.impl;

import com.algotrader.strategy.base.PositionalStrategyConfig;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Configuration for the bear put spread strategy.
 *
 * <p>Extends {@link PositionalStrategyConfig} with directional spread parameters:
 * buy/sell offsets from ATM for the two PE legs, maximum spot price for entry
 * (bearish confirmation), and a P&L roll threshold for defensive adjustments.
 *
 * <p>A bear put spread buys a higher-strike PE and sells a lower-strike PE.
 * It profits from downward movement in the underlying. Max profit is capped at
 * (buyStrike - sellStrike - net premium paid) per lot. Max loss = net premium paid.
 *
 * <p><b>Leg layout example (NIFTY at 22000, buyOffset=0, sellOffset=-200):</b>
 * <pre>
 *   Sell PE 21800 (ATM - 200) | Buy PE 22000 (ATM)
 * </pre>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class BearPutSpreadConfig extends PositionalStrategyConfig {

    /**
     * Points relative to ATM for the buy (higher) PE strike.
     * Example: 0 means buy PE at ATM. 100 means buy PE at ATM + 100 (ITM).
     */
    private BigDecimal buyOffset;

    /**
     * Points relative to ATM for the sell (lower) PE strike.
     * Must be less than buyOffset to form a valid debit spread.
     * Example: -200 means sell PE at ATM - 200.
     */
    private BigDecimal sellOffset;

    /**
     * Maximum spot price for entry.
     * Ensures the strategy only enters when the underlying is at or below a
     * certain level (bearish bias confirmation).
     * Null = no maximum spot requirement.
     */
    private BigDecimal maxSpotForEntry;

    /**
     * P&L threshold (positive value) for rolling the buy leg up defensively.
     * When current P&L drops below -(rollThreshold), the buy leg is rolled up
     * by one strike interval to increase intrinsic value.
     * Null = no roll adjustment.
     * Example: 3000 means roll when P&L < -3000.
     */
    private BigDecimal rollThreshold;
}
