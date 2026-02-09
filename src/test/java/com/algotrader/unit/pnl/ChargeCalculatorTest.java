package com.algotrader.unit.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.vo.ChargeBreakdown;
import com.algotrader.pnl.ChargeCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ChargeCalculator verifying all 6 Indian market charge types
 * against manually computed reference values.
 *
 * <p>Reference trade: 75 lots of NIFTY options @ Rs 200 premium (buy and sell).
 * Buy turnover = 200 * 75 = 15,000
 * Sell turnover = 200 * 75 = 15,000
 * Total turnover = 30,000
 */
class ChargeCalculatorTest {

    private ChargeCalculator chargeCalculator;

    @BeforeEach
    void setUp() {
        chargeCalculator = new ChargeCalculator();
    }

    // ==============================
    // OPTIONS CHARGES
    // ==============================

    @Nested
    @DisplayName("Options Charges Calculation")
    class OptionsCharges {

        @Test
        @DisplayName("Brokerage is Rs 20 per order")
        void brokerage_rs20PerOrder() {
            ChargeBreakdown charges =
                    chargeCalculator.calculateOptionsCharges(new BigDecimal("200"), new BigDecimal("200"), 75, 75, 2);

            // 2 orders * Rs 20 = Rs 40
            assertThat(charges.getBrokerage()).isEqualByComparingTo("40.00");
        }

        @Test
        @DisplayName("STT is 0.0625% on sell-side premium only")
        void stt_onSellSideOnly() {
            ChargeBreakdown charges =
                    chargeCalculator.calculateOptionsCharges(new BigDecimal("200"), new BigDecimal("200"), 75, 75, 2);

            // STT = sell turnover * 0.000625 = 15000 * 0.000625 = 9.375 -> 9.38
            assertThat(charges.getStt()).isEqualByComparingTo("9.38");
        }

        @Test
        @DisplayName("Exchange charges are 0.053% on total turnover")
        void exchangeCharges_onTotalTurnover() {
            ChargeBreakdown charges =
                    chargeCalculator.calculateOptionsCharges(new BigDecimal("200"), new BigDecimal("200"), 75, 75, 2);

            // Exchange = total turnover * 0.00053 = 30000 * 0.00053 = 15.90
            assertThat(charges.getExchangeCharges()).isEqualByComparingTo("15.90");
        }

        @Test
        @DisplayName("SEBI charges are Rs 10 per crore of turnover")
        void sebiCharges_rs10PerCrore() {
            ChargeBreakdown charges =
                    chargeCalculator.calculateOptionsCharges(new BigDecimal("200"), new BigDecimal("200"), 75, 75, 2);

            // SEBI = (30000 / 10000000) * 10 = 0.03 -> 0.03
            assertThat(charges.getSebiCharges()).isEqualByComparingTo("0.03");
        }

        @Test
        @DisplayName("Stamp duty is 0.003% on buy-side only")
        void stampDuty_onBuySideOnly() {
            ChargeBreakdown charges =
                    chargeCalculator.calculateOptionsCharges(new BigDecimal("200"), new BigDecimal("200"), 75, 75, 2);

            // Stamp duty = buy turnover * 0.00003 = 15000 * 0.00003 = 0.45
            assertThat(charges.getStampDuty()).isEqualByComparingTo("0.45");
        }

        @Test
        @DisplayName("GST is 18% on brokerage + exchange + SEBI")
        void gst_onBrokerageExchangeSebi() {
            ChargeBreakdown charges =
                    chargeCalculator.calculateOptionsCharges(new BigDecimal("200"), new BigDecimal("200"), 75, 75, 2);

            // GST base = brokerage(40) + exchange(15.90) + sebi(0.03) = 55.93
            // GST = 55.93 * 0.18 = 10.0674 -> 10.07
            assertThat(charges.getGst()).isEqualByComparingTo("10.07");
        }

        @Test
        @DisplayName("Total is sum of all 6 charges")
        void total_sumOfAllCharges() {
            ChargeBreakdown charges =
                    chargeCalculator.calculateOptionsCharges(new BigDecimal("200"), new BigDecimal("200"), 75, 75, 2);

            BigDecimal expectedTotal = charges.getBrokerage()
                    .add(charges.getStt())
                    .add(charges.getExchangeCharges())
                    .add(charges.getSebiCharges())
                    .add(charges.getStampDuty())
                    .add(charges.getGst());

            assertThat(charges.getTotal()).isEqualByComparingTo(expectedTotal);
        }

        @Test
        @DisplayName("Zero quantity produces zero charges except brokerage")
        void zeroQuantity_zeroChargesExceptBrokerage() {
            ChargeBreakdown charges =
                    chargeCalculator.calculateOptionsCharges(new BigDecimal("200"), new BigDecimal("200"), 0, 0, 1);

            assertThat(charges.getBrokerage()).isEqualByComparingTo("20.00");
            assertThat(charges.getStt()).isEqualByComparingTo("0.00");
            assertThat(charges.getExchangeCharges()).isEqualByComparingTo("0.00");
            assertThat(charges.getStampDuty()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("Asymmetric buy/sell quantities calculate correctly")
        void asymmetricQuantities_calculatedCorrectly() {
            // Buy 150 @ 100, Sell 75 @ 150
            ChargeBreakdown charges =
                    chargeCalculator.calculateOptionsCharges(new BigDecimal("100"), new BigDecimal("150"), 150, 75, 3);

            // Buy turnover = 15000, Sell turnover = 11250, Total = 26250
            assertThat(charges.getBrokerage()).isEqualByComparingTo("60.00"); // 3 orders
            assertThat(charges.getStt())
                    .isEqualByComparingTo(new BigDecimal("11250")
                            .multiply(new BigDecimal("0.000625"))
                            .setScale(2, RoundingMode.HALF_UP));
            assertThat(charges.getStampDuty())
                    .isEqualByComparingTo(new BigDecimal("15000")
                            .multiply(new BigDecimal("0.00003"))
                            .setScale(2, RoundingMode.HALF_UP));
        }
    }

    // ==============================
    // FUTURES CHARGES
    // ==============================

    @Nested
    @DisplayName("Futures Charges Calculation")
    class FuturesCharges {

        @Test
        @DisplayName("Futures STT is 0.01% on sell-side")
        void stt_futuresRate() {
            ChargeBreakdown charges =
                    chargeCalculator.calculateFuturesCharges(new BigDecimal("20000"), new BigDecimal("20100"), 50, 2);

            // Sell turnover = 20100 * 50 = 1005000
            // STT = 1005000 * 0.0001 = 100.50
            assertThat(charges.getStt()).isEqualByComparingTo("100.50");
        }

        @Test
        @DisplayName("Futures exchange charges are 0.0019%")
        void exchangeCharges_futuresRate() {
            ChargeBreakdown charges =
                    chargeCalculator.calculateFuturesCharges(new BigDecimal("20000"), new BigDecimal("20100"), 50, 2);

            // Total turnover = (20000*50) + (20100*50) = 1000000 + 1005000 = 2005000
            // Exchange = 2005000 * 0.000019 = 38.095 -> 38.10
            assertThat(charges.getExchangeCharges()).isEqualByComparingTo("38.10");
        }

        @Test
        @DisplayName("Futures stamp duty is 0.002% on buy-side")
        void stampDuty_futuresRate() {
            ChargeBreakdown charges =
                    chargeCalculator.calculateFuturesCharges(new BigDecimal("20000"), new BigDecimal("20100"), 50, 2);

            // Buy turnover = 20000 * 50 = 1000000
            // Stamp = 1000000 * 0.00002 = 20.00
            assertThat(charges.getStampDuty()).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("All futures charge components sum to total")
        void total_sumOfAllCharges() {
            ChargeBreakdown charges =
                    chargeCalculator.calculateFuturesCharges(new BigDecimal("20000"), new BigDecimal("20100"), 50, 2);

            BigDecimal expectedTotal = charges.getBrokerage()
                    .add(charges.getStt())
                    .add(charges.getExchangeCharges())
                    .add(charges.getSebiCharges())
                    .add(charges.getStampDuty())
                    .add(charges.getGst());

            assertThat(charges.getTotal()).isEqualByComparingTo(expectedTotal);
        }
    }

    // ==============================
    // ESTIMATE (Pre-trade)
    // ==============================

    @Nested
    @DisplayName("Options Charge Estimation")
    class OptionsEstimate {

        @Test
        @DisplayName("Sell-side estimate includes STT but not stamp duty")
        void sellEstimate_includesSTT() {
            BigDecimal estimate = chargeCalculator.estimateOptionsCharges(new BigDecimal("200"), 75, OrderSide.SELL);

            // Turnover = 200 * 75 = 15000
            // Brokerage = 20
            // STT = 15000 * 0.000625 = 9.375
            // Exchange = 15000 * 0.00053 = 7.95
            // SEBI = (15000/10000000)*10 = 0.015
            // Stamp = 0 (sell side)
            // GST = (20 + 7.95 + 0.015) * 0.18 = 5.0337
            // Total ~= 42.37
            assertThat(estimate).isPositive();
            assertThat(estimate.compareTo(new BigDecimal("40")) > 0).isTrue();
        }

        @Test
        @DisplayName("Buy-side estimate includes stamp duty but not STT")
        void buyEstimate_includesStampDuty() {
            BigDecimal estimate = chargeCalculator.estimateOptionsCharges(new BigDecimal("200"), 75, OrderSide.BUY);

            // STT = 0 (buy side)
            // Stamp = 15000 * 0.00003 = 0.45
            assertThat(estimate).isPositive();
            // Buy estimate should be less than sell (no STT)
            BigDecimal sellEstimate =
                    chargeCalculator.estimateOptionsCharges(new BigDecimal("200"), 75, OrderSide.SELL);
            assertThat(estimate).isLessThan(sellEstimate);
        }

        @Test
        @DisplayName("Estimate returns positive value for any valid trade")
        void estimate_alwaysPositive() {
            BigDecimal estimate = chargeCalculator.estimateOptionsCharges(new BigDecimal("50"), 25, OrderSide.SELL);

            assertThat(estimate).isPositive();
        }
    }

    // ==============================
    // KNOWN REFERENCE VALUES
    // ==============================

    @Nested
    @DisplayName("Known Reference Values")
    class ReferenceValues {

        @Test
        @DisplayName("75 NIFTY lots @ Rs 200 premium (buy+sell) -- all charges correct")
        void nifty75Lots_allChargesCorrect() {
            // Reference: 75 NIFTY options, buy @ 200, sell @ 200, 2 orders
            // Buy turnover = 15000, Sell turnover = 15000, Total = 30000
            ChargeBreakdown charges =
                    chargeCalculator.calculateOptionsCharges(new BigDecimal("200"), new BigDecimal("200"), 75, 75, 2);

            // Brokerage: 2 * 20 = 40.00
            assertThat(charges.getBrokerage()).isEqualByComparingTo("40.00");
            // STT: 15000 * 0.000625 = 9.375 -> 9.38
            assertThat(charges.getStt()).isEqualByComparingTo("9.38");
            // Exchange: 30000 * 0.00053 = 15.90
            assertThat(charges.getExchangeCharges()).isEqualByComparingTo("15.90");
            // SEBI: (30000/10000000)*10 = 0.03
            assertThat(charges.getSebiCharges()).isEqualByComparingTo("0.03");
            // Stamp: 15000 * 0.00003 = 0.45
            assertThat(charges.getStampDuty()).isEqualByComparingTo("0.45");
            // GST: (40 + 15.90 + 0.03) * 0.18 = 55.93 * 0.18 = 10.0674 -> 10.07
            assertThat(charges.getGst()).isEqualByComparingTo("10.07");
            // Total: 40 + 9.38 + 15.90 + 0.03 + 0.45 + 10.07 = 75.83
            assertThat(charges.getTotal()).isEqualByComparingTo("75.83");
        }

        @Test
        @DisplayName("Large trade: 1000 lots @ Rs 500 premium")
        void largeTrade_correctCharges() {
            // Buy turnover = 500 * 1000 = 500000
            // Sell turnover = 500 * 1000 = 500000
            // Total turnover = 1000000
            ChargeBreakdown charges = chargeCalculator.calculateOptionsCharges(
                    new BigDecimal("500"), new BigDecimal("500"), 1000, 1000, 4);

            // Brokerage: 4 * 20 = 80
            assertThat(charges.getBrokerage()).isEqualByComparingTo("80.00");
            // STT: 500000 * 0.000625 = 312.50
            assertThat(charges.getStt()).isEqualByComparingTo("312.50");
            // Exchange: 1000000 * 0.00053 = 530.00
            assertThat(charges.getExchangeCharges()).isEqualByComparingTo("530.00");
            // SEBI: (1000000/10000000)*10 = 1.00
            assertThat(charges.getSebiCharges()).isEqualByComparingTo("1.00");
            // Stamp: 500000 * 0.00003 = 15.00
            assertThat(charges.getStampDuty()).isEqualByComparingTo("15.00");
            // GST: (80 + 530 + 1) * 0.18 = 611 * 0.18 = 109.98
            assertThat(charges.getGst()).isEqualByComparingTo("109.98");
        }
    }
}
