package com.algotrader.domain.model;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * Input context for position sizing calculations.
 *
 * <p>Provides the {@link com.algotrader.margin.PositionSizer} implementations with all
 * the data they need to determine the number of lots. Built by the strategy engine or
 * caller before invoking the sizer.
 *
 * <p>The maxLossPerLot field is strategy-specific: for an iron condor it equals
 * (wingWidth * lotSize - premiumCollected), for a naked option it's theoretically
 * unlimited (capped by stop loss). Required only by the RISK_BASED sizer.
 */
@Data
@Builder
public class PositionSizingContext {

    /** Margin available for new positions. */
    private BigDecimal availableMargin;

    /** Total account capital (available + used margin). */
    private BigDecimal totalCapital;

    /** Margin required per lot for this instrument/strategy. */
    private BigDecimal marginPerLot;

    /** Maximum loss per lot (strategy-specific). Required for RISK_BASED sizing. */
    private BigDecimal maxLossPerLot;

    /** Exchange lot size for the instrument (e.g., NIFTY=75, BANKNIFTY=30). */
    private int lotSize;

    /** Underlying symbol (e.g., "NIFTY", "BANKNIFTY"). */
    private String underlying;

    /** Hard upper limit on lots (from risk limits or strategy config). */
    private int maxLotsAllowed;
}
