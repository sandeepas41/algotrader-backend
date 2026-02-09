package com.algotrader.strategy.impl;

import com.algotrader.strategy.base.PositionalStrategyConfig;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Configuration for the bear call spread (credit call spread) strategy.
 *
 * <p>Extends {@link PositionalStrategyConfig} with directional spread parameters:
 * sell/buy offsets from ATM for the two CE legs, maximum spot price for entry
 * (bearish confirmation), and a P&L roll threshold for defensive adjustments.
 *
 * <p>A bear call spread sells a lower-strike CE and buys a higher-strike CE.
 * It is a net credit strategy that profits from the underlying staying below the
 * short call strike. Max profit = net premium collected. Max loss = (buyStrike -
 * sellStrike - net premium) per lot.
 *
 * <p><b>Leg layout example (NIFTY at 22000, sellOffset=100, buyOffset=300):</b>
 * <pre>
 *   Sell CE 22100 (ATM + 100) | Buy CE 22300 (ATM + 300)
 * </pre>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class BearCallSpreadConfig extends PositionalStrategyConfig {

    /**
     * Points above ATM for the sell (lower) CE strike.
     * Example: 100 means sell CE at ATM + 100.
     */
    private BigDecimal sellOffset;

    /**
     * Points above ATM for the buy (higher) CE strike.
     * Must be greater than sellOffset to form a valid credit spread.
     * Example: 300 means buy CE at ATM + 300.
     */
    private BigDecimal buyOffset;

    /**
     * Maximum spot price for entry.
     * Ensures the strategy only enters when the underlying is at or below a
     * certain level (bearish bias confirmation).
     * Null = no maximum spot requirement.
     */
    private BigDecimal maxSpotForEntry;

    /**
     * P&L threshold (positive value) for rolling the sell leg up defensively.
     * When current P&L drops below -(rollThreshold), the sell leg is rolled up
     * by one strike interval to move away from the underlying.
     * Null = no roll adjustment.
     * Example: 3000 means roll when P&L < -3000.
     */
    private BigDecimal rollThreshold;
}
