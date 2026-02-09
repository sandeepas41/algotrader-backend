package com.algotrader.strategy.base;

import java.math.BigDecimal;
import java.time.Duration;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Configuration for positional strategies (5-min evaluation interval).
 *
 * <p>Extends {@link BaseStrategyConfig} with percentage-based exit conditions:
 * target profit as a percentage of entry premium, stop-loss as a multiplier of
 * entry premium, and minimum days-to-expiry for time-based exits.
 *
 * <p>Used by: Iron Condor, Straddle, Strangle, Bull Call Spread, Bear Put Spread,
 * and all other positional-only strategy types. Dual-mode strategies (NakedOption,
 * LongStraddle) extend BaseStrategyConfig directly with both positional and scalping exits.
 *
 * <p>The adjustment cooldown defaults to 5 minutes for positional strategies.
 * This prevents rapid-fire adjustments when a position is oscillating near a threshold.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class PositionalStrategyConfig extends BaseStrategyConfig {

    /**
     * Target profit as a fraction of entry premium.
     * Example: 0.5 = close when 50% of collected premium is profit.
     */
    private BigDecimal targetPercent;

    /**
     * Stop-loss as a multiplier of entry premium.
     * Example: 2.0 = close when loss equals 2x the collected premium.
     */
    private BigDecimal stopLossMultiplier;

    /**
     * Minimum days to expiry before time-based exit triggers.
     * Example: 1 = exit if DTE <= 1.
     */
    private int minDaysToExpiry;

    /**
     * Adjustment cooldown duration. Defaults to 5 minutes for positional strategies.
     * Overridable per-strategy for cases where faster adjustments are needed.
     */
    @lombok.Builder.Default
    private Duration adjustmentCooldown = Duration.ofMinutes(5);
}
