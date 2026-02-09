package com.algotrader.strategy.base;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Common configuration shared by all strategy types.
 *
 * <p>Contains the minimum fields every strategy needs: underlying instrument, expiry,
 * lot count, and entry time window. Positional strategies extend this with
 * {@link PositionalStrategyConfig} (target %, stop-loss multiplier, min DTE).
 * Dual-mode strategies (NakedOptionConfig, LongStraddleConfig) extend this directly
 * with both positional and scalping exit fields, toggled by a scalpingMode flag.
 *
 * <p>Uses {@code @SuperBuilder} so subclasses can chain builder calls:
 * {@code PositionalStrategyConfig.builder().underlying("NIFTY").targetPercent(0.5).build()}.
 */
@Data
@SuperBuilder
@NoArgsConstructor
public class BaseStrategyConfig {

    /** Root underlying symbol, e.g., "NIFTY", "BANKNIFTY". */
    private String underlying;

    /** Target expiry date for positions. */
    private LocalDate expiry;

    /** Number of lots to trade. */
    private int lots;

    /** Earliest time to enter positions (IST). */
    private LocalTime entryStartTime;

    /** Latest time to enter positions (IST). */
    private LocalTime entryEndTime;

    /**
     * Strike interval for the underlying.
     * NIFTY: 50, BANKNIFTY: 100. Used for rounding to nearest strike.
     */
    private BigDecimal strikeInterval;

    /**
     * Auto-pause P&L threshold: if strategy P&L drops below this (negative), auto-pause.
     * Null = disabled. Example: -15000 means auto-pause if losing more than 15k.
     */
    private BigDecimal autoPausePnlThreshold;

    /**
     * Auto-pause delta threshold: if absolute position delta exceeds this, auto-pause.
     * Null = disabled. Example: 0.5 means auto-pause if net delta > 0.5 or < -0.5.
     */
    private BigDecimal autoPauseDeltaThreshold;
}
