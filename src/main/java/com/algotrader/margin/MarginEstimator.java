package com.algotrader.margin;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.domain.model.MarginEstimate;
import com.algotrader.exception.BrokerException;
import com.algotrader.oms.OrderRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Estimates margin requirements for proposed trades before they are placed.
 *
 * <p>Provides two estimation modes:
 * <ul>
 *   <li><b>Single order:</b> Uses {@link BrokerGateway#getOrderMargin(OrderRequest)} to get
 *       the exact margin for one leg. Used by the order entry UI for pre-trade checks.</li>
 *   <li><b>Multi-leg basket:</b> Uses {@link BrokerGateway#getBasketMargin(List)} which
 *       accounts for hedging benefits (e.g., an iron condor requires much less margin than
 *       4 naked legs). Computes hedge benefit as the difference between individual sum and
 *       combined margin.</li>
 * </ul>
 *
 * <p>Both modes compare the required margin against the current available margin to
 * determine if the trade is affordable, and compute the shortfall if not.
 */
@Service
public class MarginEstimator {

    private static final Logger log = LoggerFactory.getLogger(MarginEstimator.class);

    private final BrokerGateway brokerGateway;
    private final MarginService marginService;

    public MarginEstimator(BrokerGateway brokerGateway, MarginService marginService) {
        this.brokerGateway = brokerGateway;
        this.marginService = marginService;
    }

    /**
     * Estimates margin for a single order using Kite's order margins API.
     *
     * @param orderRequest the proposed order
     * @return margin estimate with sufficiency check
     * @throws BrokerException if the margin API call fails
     */
    public MarginEstimate estimateSingleOrder(OrderRequest orderRequest) {
        try {
            BigDecimal requiredMargin = brokerGateway.getOrderMargin(orderRequest);
            BigDecimal availableMargin = marginService.getAvailableMargin();

            return MarginEstimate.builder()
                    .requiredMargin(requiredMargin)
                    .availableMargin(availableMargin)
                    .sufficient(availableMargin.compareTo(requiredMargin) >= 0)
                    .shortfall(
                            availableMargin.compareTo(requiredMargin) < 0
                                    ? requiredMargin.subtract(availableMargin)
                                    : BigDecimal.ZERO)
                    .build();

        } catch (Exception e) {
            log.error("Failed to estimate margin for order: {}", orderRequest.getTradingSymbol(), e);
            throw new BrokerException("Margin estimation failed", e);
        }
    }

    /**
     * Estimates combined margin for a multi-leg strategy order basket.
     *
     * <p>Uses Kite's basket margins API which natively calculates portfolio-level margin
     * with hedging benefits. For example, an iron condor (4 legs) may require only 40-60%
     * of the sum of individual leg margins due to the protective wings.
     *
     * @param orders the list of order requests forming the basket
     * @return margin estimate with hedge benefit analysis
     * @throws BrokerException if the margin API call fails
     */
    public MarginEstimate estimateMultiLeg(List<OrderRequest> orders) {
        try {
            // Combined margin with hedging benefits
            BigDecimal combinedMargin = brokerGateway.getBasketMargin(orders);

            // Sum of individual leg margins for comparison
            BigDecimal sumOfIndividualMargins = BigDecimal.ZERO;
            for (OrderRequest order : orders) {
                BigDecimal legMargin = brokerGateway.getOrderMargin(order);
                sumOfIndividualMargins = sumOfIndividualMargins.add(legMargin);
            }

            BigDecimal hedgeBenefit = sumOfIndividualMargins.subtract(combinedMargin);
            BigDecimal availableMargin = marginService.getAvailableMargin();

            BigDecimal hedgeBenefitPercent = sumOfIndividualMargins.compareTo(BigDecimal.ZERO) > 0
                    ? hedgeBenefit
                            .divide(sumOfIndividualMargins, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;

            return MarginEstimate.builder()
                    .requiredMargin(combinedMargin)
                    .individualMarginSum(sumOfIndividualMargins)
                    .hedgeBenefit(hedgeBenefit)
                    .hedgeBenefitPercent(hedgeBenefitPercent.setScale(2, RoundingMode.HALF_UP))
                    .availableMargin(availableMargin)
                    .sufficient(availableMargin.compareTo(combinedMargin) >= 0)
                    .shortfall(
                            availableMargin.compareTo(combinedMargin) < 0
                                    ? combinedMargin.subtract(availableMargin)
                                    : BigDecimal.ZERO)
                    .legCount(orders.size())
                    .build();

        } catch (Exception e) {
            log.error("Failed to estimate multi-leg margin for {} orders", orders.size(), e);
            throw new BrokerException("Multi-leg margin estimation failed", e);
        }
    }
}
