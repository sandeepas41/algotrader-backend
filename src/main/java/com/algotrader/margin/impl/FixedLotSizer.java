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
 * Always trades a fixed number of lots, regardless of account size or risk.
 *
 * <p>The simplest sizing strategy, suitable for beginners or when running
 * strategies with small, predictable position sizes. The fixed lot count
 * is configured via {@link PositionSizingConfig#getFixedLots()}.
 *
 * <p>Margin-aware: if the available margin cannot support the configured lots,
 * the result is reduced to the maximum affordable lots. The result is also
 * capped at {@link PositionSizingContext#getMaxLotsAllowed()}.
 */
@Component
public class FixedLotSizer implements PositionSizer {

    private static final Logger log = LoggerFactory.getLogger(FixedLotSizer.class);

    private final PositionSizingConfig positionSizingConfig;

    public FixedLotSizer(PositionSizingConfig positionSizingConfig) {
        this.positionSizingConfig = positionSizingConfig;
    }

    @Override
    public int calculateLots(PositionSizingContext positionSizingContext) {
        int fixedLots = positionSizingConfig.getFixedLots();

        // Check if margin can support the desired lots
        BigDecimal requiredMargin = positionSizingContext.getMarginPerLot().multiply(BigDecimal.valueOf(fixedLots));

        if (requiredMargin.compareTo(positionSizingContext.getAvailableMargin()) > 0) {
            int affordableLots = positionSizingContext
                    .getAvailableMargin()
                    .divide(positionSizingContext.getMarginPerLot(), 0, RoundingMode.DOWN)
                    .intValue();
            log.warn("Requested {} lots but only {} affordable with available margin", fixedLots, affordableLots);
            return Math.min(affordableLots, positionSizingContext.getMaxLotsAllowed());
        }

        return Math.min(fixedLots, positionSizingContext.getMaxLotsAllowed());
    }

    @Override
    public PositionSizingType getType() {
        return PositionSizingType.FIXED_LOTS;
    }
}
