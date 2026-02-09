package com.algotrader.margin.impl;

import com.algotrader.domain.enums.PositionSizingType;
import com.algotrader.domain.model.PositionSizingContext;
import com.algotrader.margin.PositionSizer;
import com.algotrader.margin.PositionSizingConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Allocates a percentage of total capital per trade, then converts to lot count.
 *
 * <p>Formula: lots = (totalCapital * capitalPercentage / 100) / marginPerLot.
 * For example, with Rs 10L capital, 5% allocation, and Rs 1L margin per lot,
 * the result is 0.5 lots, which rounds down to 0 -- so a minimum of 1 lot is
 * enforced to avoid no-trade situations.
 *
 * <p>This sizing strategy scales naturally with account growth: a larger account
 * takes proportionally larger positions, maintaining consistent risk exposure.
 *
 * <p>The result is capped at both the margin-affordable count and the max lots
 * from risk limits.
 */
@Component
public class PercentageOfCapitalSizer implements PositionSizer {

    private static final Logger log = LoggerFactory.getLogger(PercentageOfCapitalSizer.class);

    private final PositionSizingConfig positionSizingConfig;

    public PercentageOfCapitalSizer(PositionSizingConfig positionSizingConfig) {
        this.positionSizingConfig = positionSizingConfig;
    }

    @Override
    public int calculateLots(PositionSizingContext positionSizingContext) {
        BigDecimal percentage = positionSizingConfig.getCapitalPercentage();

        // Capital to allocate = totalCapital * percentage / 100
        BigDecimal capitalToAllocate = positionSizingContext
                .getTotalCapital()
                .multiply(percentage)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        // Lots = capitalToAllocate / marginPerLot
        int lots = capitalToAllocate
                .divide(positionSizingContext.getMarginPerLot(), 0, RoundingMode.DOWN)
                .intValue();

        // Ensure within available margin
        BigDecimal requiredMargin = positionSizingContext.getMarginPerLot().multiply(BigDecimal.valueOf(lots));
        if (requiredMargin.compareTo(positionSizingContext.getAvailableMargin()) > 0) {
            lots = positionSizingContext
                    .getAvailableMargin()
                    .divide(positionSizingContext.getMarginPerLot(), 0, RoundingMode.DOWN)
                    .intValue();
        }

        // Minimum 1 lot, capped at max
        lots = Math.max(1, lots);
        lots = Math.min(lots, positionSizingContext.getMaxLotsAllowed());

        log.debug(
                "PercentageOfCapital sizing: {}% of {} = {} lots",
                percentage, positionSizingContext.getTotalCapital(), lots);

        return lots;
    }

    @Override
    public PositionSizingType getType() {
        return PositionSizingType.PERCENTAGE_OF_CAPITAL;
    }
}
