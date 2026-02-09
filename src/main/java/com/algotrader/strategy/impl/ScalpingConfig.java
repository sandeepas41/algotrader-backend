package com.algotrader.strategy.impl;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.strategy.base.BaseStrategyConfig;
import java.math.BigDecimal;
import java.time.Duration;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Configuration for the scalping strategy.
 *
 * <p>Extends {@link BaseStrategyConfig} directly (not PositionalStrategyConfig) because
 * scalping uses point-based exits instead of percentage-based exits, and has unique
 * parameters like max hold duration and auto-entry control.
 *
 * <p>Scalping is an ultra-fast intraday strategy that enters and exits within seconds
 * to minutes. It uses tick-level monitoring (Duration.ZERO evaluation interval) and
 * a tighter 2-second stale data threshold (vs 5s for positional strategies).
 *
 * <p><b>Key differences from positional strategies:</b>
 * <ul>
 *   <li>Point-based exits (targetPoints/stopLossPoints) instead of premium percentage</li>
 *   <li>Max hold duration time exit instead of DTE exit</li>
 *   <li>No adjustments (exit fast, don't adjust)</li>
 *   <li>No morphing support</li>
 *   <li>autoEntry flag controls whether entry is automatic or manual</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class ScalpingConfig extends BaseStrategyConfig {

    /**
     * Whether to automatically enter when conditions are met.
     * If false, entries are manual (triggered externally via API).
     */
    private boolean autoEntry;

    /**
     * Option type for the scalp: "CE" or "PE".
     * Determines which option type the single-leg entry order uses.
     */
    private String optionType;

    /**
     * Explicit strike price for the scalp.
     * Unlike positional strategies that compute strikes from ATM + offset,
     * scalping uses a specific strike chosen by the trader.
     * Null = use ATM strike.
     */
    private BigDecimal strike;

    /**
     * Trade direction: BUY or SELL.
     * BUY = long premium (benefits from moves), SELL = short premium (benefits from decay).
     */
    private OrderSide side;

    /**
     * Target profit in points (absolute value).
     * Exit when P&L >= targetPoints.
     * Example: 20 means exit when profit reaches 20 points.
     */
    private BigDecimal targetPoints;

    /**
     * Stop loss in points (absolute value, applied as negative).
     * Exit when P&L <= -(stopLossPoints).
     * Example: 10 means exit when loss reaches 10 points.
     */
    private BigDecimal stopLossPoints;

    /**
     * Maximum time to hold the position before forced exit.
     * Scalps should be fast -- this prevents getting stuck in a position.
     * Example: Duration.ofMinutes(10) means close after 10 minutes.
     */
    private Duration maxHoldDuration;
}
