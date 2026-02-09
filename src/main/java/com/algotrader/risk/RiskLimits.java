package com.algotrader.risk;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * Global risk limits configuration for the trading platform.
 *
 * <p>Organized into three tiers:
 * <ul>
 *   <li><b>Position-level:</b> Max loss/profit/lots/notional per individual position</li>
 *   <li><b>Account-level:</b> Daily loss, margin utilization, max open positions/orders/strategies</li>
 *   <li><b>Strategy-level:</b> Max loss per strategy, max legs per strategy</li>
 * </ul>
 *
 * <p>Null values mean the check is disabled. For example, if maxProfitPerPosition is null,
 * the profit cap check is skipped (optional auto-exit).
 *
 * <p>Limits are loaded from application.yml (risk.limits.*) on startup and can be
 * updated at runtime via the Risk API. Changes are persisted to H2 for audit and
 * crash recovery (Task 7.2).
 */
@Data
@Builder
public class RiskLimits {

    // ==================== Position-Level Limits ====================

    /** Maximum loss allowed per position before forced exit (INR). */
    private BigDecimal maxLossPerPosition;

    /** Maximum profit target per position for optional auto-exit (INR). Null = disabled. */
    private BigDecimal maxProfitPerPosition;

    /** Maximum quantity per position in lots. */
    private Integer maxLotsPerPosition;

    /** Maximum notional value per position (INR). */
    private BigDecimal maxPositionValue;

    // ==================== Account-Level Limits ====================

    /** Maximum loss allowed per day across all positions (INR). */
    private BigDecimal dailyLossLimit;

    /** Warning threshold as fraction of daily limit (e.g., 0.8 = warn at 80%). */
    private BigDecimal dailyLossWarningThreshold;

    /** Maximum margin utilization as fraction (e.g., 0.7 = max 70%). */
    private BigDecimal maxMarginUtilization;

    /** Maximum number of open positions. */
    private Integer maxOpenPositions;

    /** Maximum number of pending orders. */
    private Integer maxOpenOrders;

    /** Maximum number of active strategies. */
    private Integer maxActiveStrategies;

    // ==================== Strategy-Level Limits ====================

    /** Maximum loss allowed per strategy (INR). */
    private BigDecimal maxLossPerStrategy;

    /** Maximum number of legs per strategy. */
    private Integer maxLegsPerStrategy;
}
