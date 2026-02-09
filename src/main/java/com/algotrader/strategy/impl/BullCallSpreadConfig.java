package com.algotrader.strategy.impl;

import com.algotrader.strategy.base.PositionalStrategyConfig;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Configuration for the bull call spread strategy.
 *
 * <p>Extends {@link PositionalStrategyConfig} with directional spread parameters:
 * buy/sell offsets from ATM for the two CE legs, minimum spot price for entry,
 * and a P&L roll threshold for defensive adjustments.
 *
 * <p>A bull call spread buys a lower-strike CE and sells a higher-strike CE.
 * It profits from upward movement in the underlying. Max profit is capped at
 * (sellStrike - buyStrike - net premium paid) per lot. Max loss = net premium paid.
 *
 * <p><b>Leg layout example (NIFTY at 22000, buyOffset=0, sellOffset=200):</b>
 * <pre>
 *   Buy CE 22000 (ATM) | Sell CE 22200 (ATM + 200)
 * </pre>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class BullCallSpreadConfig extends PositionalStrategyConfig {

    /**
     * Points above ATM for the buy (lower) CE strike.
     * Example: 0 means buy CE at ATM. -100 means buy CE at ATM - 100 (ITM).
     */
    private BigDecimal buyOffset;

    /**
     * Points above ATM for the sell (higher) CE strike.
     * Must be greater than buyOffset to form a valid debit spread.
     * Example: 200 means sell CE at ATM + 200.
     */
    private BigDecimal sellOffset;

    /**
     * Minimum spot price required for entry.
     * Ensures the strategy only enters when the underlying is at or above a
     * certain level (bullish bias confirmation).
     * Null = no minimum spot requirement.
     */
    private BigDecimal minSpotForEntry;

    /**
     * P&L threshold (positive value) for rolling the buy leg down defensively.
     * When current P&L drops below -(rollThreshold), the buy leg is rolled down
     * by one strike interval to reduce cost basis.
     * Null = no roll adjustment.
     * Example: 3000 means roll when P&L < -3000.
     */
    private BigDecimal rollThreshold;
}
