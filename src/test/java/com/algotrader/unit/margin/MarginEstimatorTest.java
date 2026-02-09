package com.algotrader.unit.margin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.MarginEstimate;
import com.algotrader.exception.BrokerException;
import com.algotrader.margin.MarginEstimator;
import com.algotrader.margin.MarginService;
import com.algotrader.oms.OrderRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for MarginEstimator covering single-order and multi-leg estimation,
 * sufficiency checks, hedge benefit calculations, and error handling.
 */
@ExtendWith(MockitoExtension.class)
class MarginEstimatorTest {

    @Mock
    private BrokerGateway brokerGateway;

    @Mock
    private MarginService marginService;

    private MarginEstimator marginEstimator;

    @BeforeEach
    void setUp() {
        marginEstimator = new MarginEstimator(brokerGateway, marginService);
    }

    private OrderRequest sampleOrder() {
        return OrderRequest.builder()
                .tradingSymbol("NIFTY24FEB22000CE")
                .exchange("NFO")
                .side(OrderSide.SELL)
                .type(OrderType.LIMIT)
                .quantity(75)
                .price(new BigDecimal("200"))
                .build();
    }

    // ==============================
    // SINGLE ORDER ESTIMATION
    // ==============================

    @Nested
    @DisplayName("Single Order Estimation")
    class SingleOrderEstimation {

        @Test
        @DisplayName("Returns sufficient when available margin exceeds required")
        void sufficientMargin() {
            when(brokerGateway.getOrderMargin(any())).thenReturn(new BigDecimal("100000"));
            when(marginService.getAvailableMargin()).thenReturn(new BigDecimal("500000"));

            MarginEstimate estimate = marginEstimator.estimateSingleOrder(sampleOrder());

            assertThat(estimate.getRequiredMargin()).isEqualByComparingTo("100000");
            assertThat(estimate.getAvailableMargin()).isEqualByComparingTo("500000");
            assertThat(estimate.isSufficient()).isTrue();
            assertThat(estimate.getShortfall()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("Returns insufficient with correct shortfall")
        void insufficientMargin() {
            when(brokerGateway.getOrderMargin(any())).thenReturn(new BigDecimal("600000"));
            when(marginService.getAvailableMargin()).thenReturn(new BigDecimal("500000"));

            MarginEstimate estimate = marginEstimator.estimateSingleOrder(sampleOrder());

            assertThat(estimate.isSufficient()).isFalse();
            assertThat(estimate.getShortfall()).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("Sufficient when available equals required exactly")
        void exactlyEqual() {
            when(brokerGateway.getOrderMargin(any())).thenReturn(new BigDecimal("100000"));
            when(marginService.getAvailableMargin()).thenReturn(new BigDecimal("100000"));

            MarginEstimate estimate = marginEstimator.estimateSingleOrder(sampleOrder());

            assertThat(estimate.isSufficient()).isTrue();
            assertThat(estimate.getShortfall()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("Throws BrokerException on broker API failure")
        void throwsOnBrokerError() {
            when(brokerGateway.getOrderMargin(any())).thenThrow(new RuntimeException("timeout"));

            assertThatThrownBy(() -> marginEstimator.estimateSingleOrder(sampleOrder()))
                    .isInstanceOf(BrokerException.class)
                    .hasMessageContaining("Margin estimation failed");
        }
    }

    // ==============================
    // MULTI-LEG ESTIMATION
    // ==============================

    @Nested
    @DisplayName("Multi-Leg Estimation")
    class MultiLegEstimation {

        @Test
        @DisplayName("Calculates hedge benefit for iron condor")
        void hedgeBenefitCalculation() {
            OrderRequest leg1 = sampleOrder();
            OrderRequest leg2 = sampleOrder();
            OrderRequest leg3 = sampleOrder();
            OrderRequest leg4 = sampleOrder();
            List<OrderRequest> basket = List.of(leg1, leg2, leg3, leg4);

            // Combined margin is less than sum of individuals (hedging benefit)
            when(brokerGateway.getBasketMargin(basket)).thenReturn(new BigDecimal("100000"));
            when(brokerGateway.getOrderMargin(any())).thenReturn(new BigDecimal("50000"));
            when(marginService.getAvailableMargin()).thenReturn(new BigDecimal("500000"));

            MarginEstimate estimate = marginEstimator.estimateMultiLeg(basket);

            assertThat(estimate.getRequiredMargin()).isEqualByComparingTo("100000");
            // 4 legs * 50000 = 200000
            assertThat(estimate.getIndividualMarginSum()).isEqualByComparingTo("200000");
            // Hedge benefit = 200000 - 100000 = 100000
            assertThat(estimate.getHedgeBenefit()).isEqualByComparingTo("100000");
            // 100000/200000 * 100 = 50%
            assertThat(estimate.getHedgeBenefitPercent()).isEqualByComparingTo("50.00");
            assertThat(estimate.getLegCount()).isEqualTo(4);
            assertThat(estimate.isSufficient()).isTrue();
        }

        @Test
        @DisplayName("Returns insufficient with correct shortfall for multi-leg")
        void insufficientMultiLeg() {
            OrderRequest leg1 = sampleOrder();
            OrderRequest leg2 = sampleOrder();
            List<OrderRequest> basket = List.of(leg1, leg2);

            when(brokerGateway.getBasketMargin(basket)).thenReturn(new BigDecimal("300000"));
            when(brokerGateway.getOrderMargin(any())).thenReturn(new BigDecimal("200000"));
            when(marginService.getAvailableMargin()).thenReturn(new BigDecimal("200000"));

            MarginEstimate estimate = marginEstimator.estimateMultiLeg(basket);

            assertThat(estimate.isSufficient()).isFalse();
            assertThat(estimate.getShortfall()).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("Handles zero individual margin sum (no division by zero)")
        void zeroIndividualMargin() {
            OrderRequest leg1 = sampleOrder();
            List<OrderRequest> basket = List.of(leg1);

            when(brokerGateway.getBasketMargin(basket)).thenReturn(BigDecimal.ZERO);
            when(brokerGateway.getOrderMargin(any())).thenReturn(BigDecimal.ZERO);
            when(marginService.getAvailableMargin()).thenReturn(new BigDecimal("500000"));

            MarginEstimate estimate = marginEstimator.estimateMultiLeg(basket);

            assertThat(estimate.getHedgeBenefitPercent()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("Throws BrokerException on basket API failure")
        void throwsOnBasketError() {
            when(brokerGateway.getBasketMargin(any())).thenThrow(new RuntimeException("API error"));

            assertThatThrownBy(() -> marginEstimator.estimateMultiLeg(List.of(sampleOrder())))
                    .isInstanceOf(BrokerException.class)
                    .hasMessageContaining("Multi-leg margin estimation failed");
        }
    }
}
