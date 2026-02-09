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
 * Sizes positions based on maximum acceptable loss per trade.
 *
 * <p>Formula: lots = (totalCapital * riskPercentage / 100) / maxLossPerLot.
 * For example, with Rs 10L capital, 2% risk, and Rs 5000 max loss per lot,
 * the result is 4 lots (risking Rs 20,000 total).
 *
 * <p>This is the most disciplined sizing strategy, commonly used in professional
 * trading. It ensures that no single trade can lose more than a fixed percentage
 * of total capital, regardless of the instrument or strategy.
 *
 * <p>Requires the caller to provide {@link PositionSizingContext#getMaxLossPerLot()},
 * which is strategy-specific:
 * <ul>
 *   <li>Iron Condor: wingWidth * lotSize - premiumCollected</li>
 *   <li>Straddle: stop loss points * lotSize</li>
 *   <li>Bull Call Spread: spreadWidth * lotSize - premiumPaid</li>
 * </ul>
 *
 * <p>If maxLossPerLot is null or zero (unknown), falls back to 1 lot for safety.
 */
@Component
public class RiskBasedSizer implements PositionSizer {

    private static final Logger log = LoggerFactory.getLogger(RiskBasedSizer.class);

    private final PositionSizingConfig positionSizingConfig;

    public RiskBasedSizer(PositionSizingConfig positionSizingConfig) {
        this.positionSizingConfig = positionSizingConfig;
    }

    @Override
    public int calculateLots(PositionSizingContext positionSizingContext) {
        BigDecimal riskPercentage = positionSizingConfig.getRiskPercentage();

        // Max loss allowed = totalCapital * riskPercentage / 100
        BigDecimal maxLossAllowed = positionSizingContext
                .getTotalCapital()
                .multiply(riskPercentage)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        // Fallback to 1 lot if max loss per lot is unknown
        if (positionSizingContext.getMaxLossPerLot() == null
                || positionSizingContext.getMaxLossPerLot().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Max loss per lot not available, falling back to 1 lot");
            return 1;
        }

        // Lots = maxLossAllowed / maxLossPerLot
        int lots = maxLossAllowed
                .divide(positionSizingContext.getMaxLossPerLot(), 0, RoundingMode.DOWN)
                .intValue();

        // Ensure within margin constraints
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
                "RiskBased sizing: {}% risk of {} = max loss {} = {} lots",
                riskPercentage, positionSizingContext.getTotalCapital(), maxLossAllowed, lots);

        return lots;
    }

    @Override
    public PositionSizingType getType() {
        return PositionSizingType.RISK_BASED;
    }
}
