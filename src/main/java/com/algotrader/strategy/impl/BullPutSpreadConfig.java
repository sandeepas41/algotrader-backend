package com.algotrader.strategy.impl;

import com.algotrader.strategy.base.PositionalStrategyConfig;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Configuration for the bull put spread (credit put spread) strategy.
 *
 * <p>Extends {@link PositionalStrategyConfig} with directional spread parameters:
 * sell/buy offsets from ATM for the two PE legs, minimum spot price for entry
 * (bullish confirmation), and a P&L roll threshold for defensive adjustments.
 *
 * <p>A bull put spread sells a higher-strike PE and buys a lower-strike PE.
 * It is a net credit strategy that profits from the underlying staying above the
 * short put strike. Max profit = net premium collected. Max loss = (sellStrike -
 * buyStrike - net premium) per lot.
 *
 * <p><b>Leg layout example (NIFTY at 22000, sellOffset=-100, buyOffset=-300):</b>
 * <pre>
 *   Buy PE 21700 (ATM - 300) | Sell PE 21900 (ATM - 100)
 * </pre>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class BullPutSpreadConfig extends PositionalStrategyConfig {

    /**
     * Points relative to ATM for the sell (higher) PE strike.
     * Example: -100 means sell PE at ATM - 100.
     */
    private BigDecimal sellOffset;

    /**
     * Points relative to ATM for the buy (lower) PE strike.
     * Must be less than sellOffset to form a valid credit spread.
     * Example: -300 means buy PE at ATM - 300.
     */
    private BigDecimal buyOffset;

    /**
     * Minimum spot price for entry.
     * Ensures the strategy only enters when the underlying is at or above a
     * certain level (bullish bias confirmation).
     * Null = no minimum spot requirement.
     */
    private BigDecimal minSpotForEntry;

    /**
     * P&L threshold (positive value) for rolling the sell leg down defensively.
     * When current P&L drops below -(rollThreshold), the sell leg is rolled down
     * by one strike interval to reduce credit risk.
     * Null = no roll adjustment.
     * Example: 3000 means roll when P&L < -3000.
     */
    private BigDecimal rollThreshold;
}
