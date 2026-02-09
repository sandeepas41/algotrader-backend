package com.algotrader.strategy.impl;

import com.algotrader.strategy.base.BaseStrategyConfig;
import java.math.BigDecimal;
import java.time.Duration;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Configuration for naked (single-leg) option strategies: CE_BUY, CE_SELL, PE_BUY, PE_SELL.
 *
 * <p>Extends {@link BaseStrategyConfig} directly (like ScalpingConfig) because it supports
 * two operating modes with different exit parameters:
 * <ul>
 *   <li><b>Positional mode</b> (scalpingMode=false, default): 5-min evaluation interval,
 *       percentage-based exits (targetPercent, stopLossMultiplier, minDaysToExpiry)</li>
 *   <li><b>Scalping mode</b> (scalpingMode=true): tick-level evaluation, point-based exits
 *       (targetPoints, stopLossPoints, maxHoldDuration)</li>
 * </ul>
 *
 * <p>Both sets of exit fields are present in the config. Only the set matching the current
 * mode is evaluated by {@link NakedOptionStrategy}. The option type (CE/PE) and side (BUY/SELL)
 * are determined by the {@link com.algotrader.domain.enums.StrategyType}, not by config fields.
 *
 * <p>Strike selection: if {@code strike} is null, ATM is used. {@code strikeOffset} shifts
 * from ATM in strike intervals (positive = OTM, negative = ITM).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class NakedOptionConfig extends BaseStrategyConfig {

    /**
     * Explicit strike price. Null = use ATM (adjusted by strikeOffset).
     * When set, strikeOffset is ignored.
     */
    private BigDecimal strike;

    /**
     * Strike offset from ATM in number of strike intervals.
     * 0 = ATM, +N = OTM strikes away, -N = ITM strikes away.
     * Only used when strike is null. Example: strikeOffset=2 with NIFTY (interval=50)
     * means 2 strikes OTM = ATM + 100 for CE, ATM - 100 for PE.
     */
    private int strikeOffset;

    /**
     * Operating mode toggle. false (default) = positional (5-min, %-based exits).
     * true = scalping (tick-level, point-based exits).
     */
    private boolean scalpingMode;

    /**
     * Whether to automatically enter when conditions are met.
     * If false, entries are manual (triggered externally via API).
     */
    private boolean autoEntry;

    // ---- Positional exit fields (used when scalpingMode=false) ----

    /**
     * Target profit as a fraction of entry premium.
     * Example: 0.5 = close when 50% of entry premium is profit.
     */
    private BigDecimal targetPercent;

    /**
     * Stop-loss as a multiplier of entry premium.
     * Example: 2.0 = close when loss equals 2x the entry premium.
     */
    private BigDecimal stopLossMultiplier;

    /**
     * Minimum days to expiry before time-based exit triggers.
     * Example: 1 = exit if DTE <= 1.
     */
    private int minDaysToExpiry;

    // ---- Scalping exit fields (used when scalpingMode=true) ----

    /**
     * Target profit in points (absolute value).
     * Exit when P&L >= targetPoints.
     */
    private BigDecimal targetPoints;

    /**
     * Stop loss in points (absolute value, applied as negative).
     * Exit when P&L <= -(stopLossPoints).
     */
    private BigDecimal stopLossPoints;

    /**
     * Maximum time to hold the position before forced exit.
     * Only used in scalping mode.
     */
    private Duration maxHoldDuration;
}
