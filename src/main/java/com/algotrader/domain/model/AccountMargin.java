package com.algotrader.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Builder;
import lombok.Data;

/**
 * Snapshot of the trading account's margin state from the broker.
 *
 * <p>Fetched from Kite's margins API and cached for 30 seconds by
 * {@link com.algotrader.margin.MarginService}. Used by the margin monitor
 * for utilization alerts and by the position sizers for margin-aware sizing.
 *
 * <p>The utilizationPercent is pre-computed as (usedMargin / totalCapital) * 100
 * to avoid repeated division in consumers.
 */
@Data
@Builder
public class AccountMargin {

    /** Cash available for trading (excluding collateral). */
    private BigDecimal availableCash;

    /** Total margin available for new positions (cash + collateral - used). */
    private BigDecimal availableMargin;

    /** Margin currently blocked for open positions. */
    private BigDecimal usedMargin;

    /** Pledged collateral (stocks, MF units). */
    private BigDecimal collateral;

    /** Total capital = availableMargin + usedMargin. */
    private BigDecimal totalCapital;

    /** Margin utilization as percentage: (usedMargin / totalCapital) * 100. */
    private BigDecimal utilizationPercent;

    /** Timestamp when this data was fetched from the broker. */
    private Instant fetchedAt;
}
