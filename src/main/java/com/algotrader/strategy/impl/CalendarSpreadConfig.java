package com.algotrader.strategy.impl;

import com.algotrader.strategy.base.PositionalStrategyConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Configuration for the calendar spread (horizontal spread) strategy.
 *
 * <p>Extends {@link PositionalStrategyConfig} with calendar-spread-specific parameters:
 * strike offset from ATM, near/far expiry dates, option type, and minimum IV for entry.
 *
 * <p>A calendar spread sells the near-term option and buys the far-term option at the
 * same strike. It profits from the near-term option decaying faster than the far-term
 * option (time spread). Maximum profit occurs when the underlying is at the strike
 * at near-term expiry.
 *
 * <p><b>Leg layout example (NIFTY at 22000, strikeOffset=0, CE options):</b>
 * <pre>
 *   Sell CE 22000 (near expiry, e.g., Feb 13) | Buy CE 22000 (far expiry, e.g., Feb 27)
 * </pre>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class CalendarSpreadConfig extends PositionalStrategyConfig {

    /**
     * Points relative to ATM for the shared strike.
     * 0 = ATM calendar (most common). Positive = OTM call, Negative = OTM put.
     * Example: 0 means both legs at ATM strike.
     */
    private BigDecimal strikeOffset;

    /**
     * Option type for both legs: "CE" or "PE".
     * CE calendars are bullish-neutral, PE calendars are bearish-neutral.
     */
    private String optionType;

    /**
     * Expiry date for the near-term (short) leg.
     * This is the leg being sold for faster theta decay.
     */
    private LocalDate nearExpiry;

    /**
     * Expiry date for the far-term (long) leg.
     * This is the leg being bought for slower theta decay.
     */
    private LocalDate farExpiry;

    /**
     * Minimum ATM implied volatility required for entry.
     * Calendar spreads benefit from moderate IV. Entering at very high IV
     * may not be ideal since IV crush hurts the long leg more.
     * Null = no IV requirement.
     */
    private BigDecimal minEntryIV;

    /**
     * P&L threshold (positive value) for closing early.
     * When current P&L drops below -(rollThreshold), close the entire spread.
     * Null = no early close adjustment.
     */
    private BigDecimal rollThreshold;
}
