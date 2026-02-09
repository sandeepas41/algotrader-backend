package com.algotrader.margin;

import com.algotrader.domain.enums.PositionSizingType;
import com.algotrader.domain.model.PositionSizingContext;

/**
 * Calculates the number of lots for a new trade based on a sizing strategy.
 *
 * <p>Implementations must respect the margin constraint: even if the sizing formula
 * produces N lots, the result must be capped at the number of lots the available
 * margin can support. The {@link PositionSizingContext#getMaxLotsAllowed()} is also
 * a hard cap from risk limits.
 *
 * <p>Three implementations exist:
 * <ul>
 *   <li>{@link com.algotrader.margin.impl.FixedLotSizer} — fixed count, simplest</li>
 *   <li>{@link com.algotrader.margin.impl.PercentageOfCapitalSizer} — scales with capital</li>
 *   <li>{@link com.algotrader.margin.impl.RiskBasedSizer} — caps max loss per trade</li>
 * </ul>
 *
 * <p>Resolved by {@link PositionSizerFactory} based on {@link PositionSizingType}.
 */
public interface PositionSizer {

    /**
     * Calculates the number of lots to trade given the sizing context.
     *
     * @param positionSizingContext account and instrument data for the sizing calculation
     * @return number of lots (always >= 0; 0 means cannot trade due to insufficient margin)
     */
    int calculateLots(PositionSizingContext positionSizingContext);

    /**
     * Returns the sizing type this implementation handles.
     */
    PositionSizingType getType();
}
