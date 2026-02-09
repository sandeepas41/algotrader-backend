package com.algotrader.strategy.impl;

import com.algotrader.strategy.base.PositionalStrategyConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Configuration for the diagonal spread strategy.
 *
 * <p>Extends {@link PositionalStrategyConfig} with diagonal-spread-specific parameters:
 * independent strike offsets for near/far legs, near/far expiry dates, option type,
 * and minimum IV for entry.
 *
 * <p>A diagonal spread is a combination of a calendar spread and a vertical spread.
 * It uses different strikes AND different expiries for the two legs. This provides
 * both theta benefit (like a calendar) and directional exposure (like a vertical).
 *
 * <p><b>Leg layout example (NIFTY at 22000, nearStrikeOffset=200, farStrikeOffset=0, CE):</b>
 * <pre>
 *   Sell CE 22200 (near expiry, OTM) | Buy CE 22000 (far expiry, ATM)
 * </pre>
 * This is a "poor man's covered call" -- buying a deep ITM/ATM LEAPS call and
 * selling a near-term OTM call against it.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class DiagonalSpreadConfig extends PositionalStrategyConfig {

    /**
     * Points relative to ATM for the near-term (short) leg strike.
     * Example: 200 means sell at ATM + 200 (OTM for CE).
     */
    private BigDecimal nearStrikeOffset;

    /**
     * Points relative to ATM for the far-term (long) leg strike.
     * Example: 0 means buy at ATM.
     */
    private BigDecimal farStrikeOffset;

    /**
     * Option type for both legs: "CE" or "PE".
     * CE diagonals are bullish, PE diagonals are bearish.
     */
    private String optionType;

    /**
     * Expiry date for the near-term (short) leg.
     */
    private LocalDate nearExpiry;

    /**
     * Expiry date for the far-term (long) leg.
     */
    private LocalDate farExpiry;

    /**
     * Minimum ATM implied volatility required for entry.
     * Null = no IV requirement.
     */
    private BigDecimal minEntryIV;

    /**
     * P&L threshold (positive value) for closing early.
     * Null = no early close adjustment.
     */
    private BigDecimal rollThreshold;
}
