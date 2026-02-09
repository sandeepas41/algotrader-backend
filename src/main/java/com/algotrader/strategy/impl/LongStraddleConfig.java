package com.algotrader.strategy.impl;

import com.algotrader.strategy.base.BaseStrategyConfig;
import java.math.BigDecimal;
import java.time.Duration;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Configuration for the long straddle strategy (BUY ATM CE + BUY ATM PE).
 *
 * <p>Extends {@link BaseStrategyConfig} directly because it supports dual operating modes:
 * <ul>
 *   <li><b>Positional</b> (scalpingMode=false, default): 5-min evaluation, %-based exits</li>
 *   <li><b>Scalping</b> (scalpingMode=true): tick-level evaluation, point-based exits</li>
 * </ul>
 *
 * <p>A long straddle buys both ATM CE and ATM PE. It profits from large moves in either
 * direction (high gamma), but loses from time decay (negative theta). Unlike the short
 * straddle, it has limited risk (premium paid) and unlimited reward.
 *
 * <p>Entry can be filtered by a minimum ATM IV requirement ({@code minIV}), similar to the
 * short straddle. For long straddles, low IV at entry is actually preferred (buy cheap),
 * but the field is available for traders who want to enter only when IV is elevated
 * (anticipating a further spike).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class LongStraddleConfig extends BaseStrategyConfig {

    /**
     * Minimum ATM implied volatility required for entry.
     * Null = no IV filter. Example: 15.0 means only enter when ATM IV >= 15%.
     */
    private BigDecimal minIV;

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
     * Example: 0.5 = close when profit equals 50% of premium paid.
     */
    private BigDecimal targetPercent;

    /**
     * Stop-loss as a multiplier of entry premium.
     * Example: 0.5 = close when 50% of premium paid is lost.
     */
    private BigDecimal stopLossMultiplier;

    /**
     * Minimum days to expiry before time-based exit triggers.
     * Important for long straddles: theta decay accelerates near expiry.
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
