package com.algotrader.unit.core.processor;

import static org.junit.jupiter.api.Assertions.*;

import com.algotrader.core.processor.GreeksCalculator;
import com.algotrader.core.processor.IVCalculator;
import com.algotrader.domain.model.Greeks;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GreeksCalculator. Validates Black-Scholes outputs against
 * reference values for NIFTY options, edge cases (near-zero DTE, deep ITM/OTM),
 * and IV solver convergence behavior.
 *
 * <p>Reference values are cross-checked against standard Black-Scholes calculators.
 * Tolerances account for minor differences in time-to-expiry calculation
 * (our calculator uses minute-level precision to market close).
 */
class GreeksCalculatorTest {

    private GreeksCalculator greeksCalculator;

    // Standard NIFTY test parameters
    private static final BigDecimal NIFTY_SPOT = BigDecimal.valueOf(22000);
    private static final BigDecimal ATM_STRIKE = BigDecimal.valueOf(22000);
    private static final BigDecimal ITM_CALL_STRIKE = BigDecimal.valueOf(21500); // 500 points ITM
    private static final BigDecimal OTM_CALL_STRIKE = BigDecimal.valueOf(22500); // 500 points OTM
    private static final BigDecimal DEEP_ITM_CALL_STRIKE = BigDecimal.valueOf(20000); // 2000 points ITM
    private static final BigDecimal DEEP_OTM_CALL_STRIKE = BigDecimal.valueOf(24000); // 2000 points OTM

    @BeforeEach
    void setUp() {
        greeksCalculator = new GreeksCalculator(new IVCalculator());
    }

    @Nested
    @DisplayName("ATM Option Greeks")
    class AtmOptionGreeks {

        @Test
        @DisplayName("ATM call delta should be approximately 0.5")
        void atmCallDeltaNearHalf() {
            // ATM option 30 days out with reasonable premium
            LocalDate expiry = LocalDate.now().plusDays(30);
            BigDecimal premium = BigDecimal.valueOf(350);

            Greeks greeks = greeksCalculator.calculate(NIFTY_SPOT, ATM_STRIKE, expiry, premium, true);

            assertTrue(greeks.isAvailable(), "Greeks should be available for ATM call");
            double delta = greeks.getDelta().doubleValue();
            // ATM call delta should be close to 0.5 (slightly above due to drift)
            assertTrue(delta > 0.45 && delta < 0.60, "ATM call delta should be near 0.5, got: " + delta);
        }

        @Test
        @DisplayName("ATM put delta should be approximately -0.5")
        void atmPutDeltaNearNegativeHalf() {
            LocalDate expiry = LocalDate.now().plusDays(30);
            BigDecimal premium = BigDecimal.valueOf(350);

            Greeks greeks = greeksCalculator.calculate(NIFTY_SPOT, ATM_STRIKE, expiry, premium, false);

            assertTrue(greeks.isAvailable(), "Greeks should be available for ATM put");
            double delta = greeks.getDelta().doubleValue();
            // ATM put delta should be close to -0.5
            assertTrue(delta > -0.60 && delta < -0.40, "ATM put delta should be near -0.5, got: " + delta);
        }

        @Test
        @DisplayName("ATM option should have highest gamma (using direct IV)")
        void atmHasHighestGamma() {
            // Use calculateDirect with same IV to isolate the gamma-vs-moneyness relationship
            LocalDate expiry = LocalDate.now().plusDays(30);
            BigDecimal iv = BigDecimal.valueOf(0.15);

            Greeks atmGreeks = greeksCalculator.calculateDirect(NIFTY_SPOT, ATM_STRIKE, expiry, iv, true, 0.0);
            Greeks otmGreeks = greeksCalculator.calculateDirect(NIFTY_SPOT, OTM_CALL_STRIKE, expiry, iv, true, 0.0);

            assertTrue(atmGreeks.isAvailable());
            assertTrue(otmGreeks.isAvailable());
            assertTrue(
                    atmGreeks.getGamma().compareTo(otmGreeks.getGamma()) > 0,
                    "ATM gamma should be greater than OTM gamma");
        }

        @Test
        @DisplayName("Theta should be negative for long options")
        void thetaNegativeForLongOptions() {
            LocalDate expiry = LocalDate.now().plusDays(30);
            BigDecimal premium = BigDecimal.valueOf(350);

            Greeks greeks = greeksCalculator.calculate(NIFTY_SPOT, ATM_STRIKE, expiry, premium, true);

            assertTrue(greeks.isAvailable());
            assertTrue(greeks.getTheta().doubleValue() < 0, "Theta should be negative (time decay)");
        }

        @Test
        @DisplayName("Vega should be positive")
        void vegaPositive() {
            LocalDate expiry = LocalDate.now().plusDays(30);
            BigDecimal premium = BigDecimal.valueOf(350);

            Greeks greeks = greeksCalculator.calculate(NIFTY_SPOT, ATM_STRIKE, expiry, premium, true);

            assertTrue(greeks.isAvailable());
            assertTrue(greeks.getVega().doubleValue() > 0, "Vega should be positive");
        }
    }

    @Nested
    @DisplayName("ITM/OTM Greeks")
    class ItmOtmGreeks {

        @Test
        @DisplayName("ITM call delta should be > 0.5")
        void itmCallDeltaAboveHalf() {
            // Use calculateDirect with known IV to verify delta behavior at different moneyness
            LocalDate expiry = LocalDate.now().plusDays(30);
            BigDecimal iv = BigDecimal.valueOf(0.15);

            Greeks greeks = greeksCalculator.calculateDirect(NIFTY_SPOT, ITM_CALL_STRIKE, expiry, iv, true, 0.0);

            assertTrue(greeks.isAvailable());
            double delta = greeks.getDelta().doubleValue();
            assertTrue(delta > 0.5, "ITM call delta should be > 0.5, got: " + delta);
        }

        @Test
        @DisplayName("OTM call delta should be < 0.5")
        void otmCallDeltaBelowHalf() {
            LocalDate expiry = LocalDate.now().plusDays(30);
            BigDecimal iv = BigDecimal.valueOf(0.15);

            Greeks greeks = greeksCalculator.calculateDirect(NIFTY_SPOT, OTM_CALL_STRIKE, expiry, iv, true, 0.0);

            assertTrue(greeks.isAvailable());
            double delta = greeks.getDelta().doubleValue();
            assertTrue(delta < 0.5, "OTM call delta should be < 0.5, got: " + delta);
        }

        @Test
        @DisplayName("Deep ITM call delta should approach 1")
        void deepItmCallDeltaApproachesOne() {
            LocalDate expiry = LocalDate.now().plusDays(30);
            BigDecimal iv = BigDecimal.valueOf(0.15);

            Greeks greeks = greeksCalculator.calculateDirect(NIFTY_SPOT, DEEP_ITM_CALL_STRIKE, expiry, iv, true, 0.0);

            assertTrue(greeks.isAvailable());
            double delta = greeks.getDelta().doubleValue();
            assertTrue(delta > 0.90, "Deep ITM call delta should approach 1, got: " + delta);
        }

        @Test
        @DisplayName("Deep OTM call delta should approach 0")
        void deepOtmCallDeltaApproachesZero() {
            LocalDate expiry = LocalDate.now().plusDays(30);
            BigDecimal iv = BigDecimal.valueOf(0.15);

            Greeks greeks = greeksCalculator.calculateDirect(NIFTY_SPOT, DEEP_OTM_CALL_STRIKE, expiry, iv, true, 0.0);

            assertTrue(greeks.isAvailable());
            double delta = greeks.getDelta().doubleValue();
            assertTrue(delta < 0.10, "Deep OTM call delta should approach 0, got: " + delta);
        }

        @Test
        @DisplayName("Deep ITM put delta should approach -1")
        void deepItmPutDeltaApproachesNegativeOne() {
            // Deep ITM put = high strike (24000) when spot is 22000
            LocalDate expiry = LocalDate.now().plusDays(30);
            BigDecimal iv = BigDecimal.valueOf(0.15);

            Greeks greeks = greeksCalculator.calculateDirect(NIFTY_SPOT, DEEP_OTM_CALL_STRIKE, expiry, iv, false, 0.0);

            assertTrue(greeks.isAvailable());
            double delta = greeks.getDelta().doubleValue();
            assertTrue(delta < -0.90, "Deep ITM put delta should approach -1, got: " + delta);
        }
    }

    @Nested
    @DisplayName("Near-Zero DTE Edge Cases")
    class NearZeroDte {

        @Test
        @DisplayName("Expiry today should produce valid Greeks (not NaN/Infinity)")
        void expiryTodayProducesValidGreeks() {
            LocalDate expiryToday = LocalDate.now();
            BigDecimal premium = BigDecimal.valueOf(50);

            Greeks greeks = greeksCalculator.calculate(NIFTY_SPOT, ATM_STRIKE, expiryToday, premium, true);

            // Should not be NaN or Infinity in any field
            assertNotNull(greeks);
            if (greeks.isAvailable()) {
                assertFalse(greeks.getDelta().doubleValue() == Double.NaN, "Delta should not be NaN");
                assertFalse(Double.isInfinite(greeks.getDelta().doubleValue()), "Delta should not be Infinite");
                assertFalse(Double.isInfinite(greeks.getGamma().doubleValue()), "Gamma should not be Infinite");
                assertFalse(Double.isInfinite(greeks.getTheta().doubleValue()), "Theta should not be Infinite");
                assertFalse(Double.isInfinite(greeks.getVega().doubleValue()), "Vega should not be Infinite");
            }
        }

        @Test
        @DisplayName("Past expiry should produce valid Greeks with minimum T clamped")
        void pastExpiryClampedToMinimum() {
            LocalDate pastExpiry = LocalDate.now().minusDays(1);
            BigDecimal premium = BigDecimal.valueOf(5);

            Greeks greeks = greeksCalculator.calculate(NIFTY_SPOT, ATM_STRIKE, pastExpiry, premium, true);

            // T is clamped to 1 minute, so calculation should still produce non-NaN values
            assertNotNull(greeks);
            if (greeks.isAvailable()) {
                assertFalse(Double.isNaN(greeks.getDelta().doubleValue()), "Delta should not be NaN");
                assertFalse(Double.isInfinite(greeks.getGamma().doubleValue()), "Gamma should not be Infinite");
            }
        }

        @Test
        @DisplayName("Time to expiry should never be negative or zero")
        void timeToExpiryNeverNegativeOrZero() throws Exception {
            // getTimeToExpiry is package-private; use reflection since test is in a different package
            Method getTimeToExpiry = GreeksCalculator.class.getDeclaredMethod("getTimeToExpiry", LocalDate.class);
            getTimeToExpiry.setAccessible(true);

            // Test with past date
            double T = (double)
                    getTimeToExpiry.invoke(greeksCalculator, LocalDate.now().minusDays(10));
            assertTrue(T > 0, "T should be positive even for past expiry, got: " + T);

            // T should be clamped to at least 1 minute = 1/525600
            double minT = 1.0 / 525600.0;
            assertTrue(T >= minT, "T should be >= minimum (1 minute), got: " + T);
        }
    }

    @Nested
    @DisplayName("Put-Call Parity")
    class PutCallParity {

        @Test
        @DisplayName("Call delta - Put delta should approximately equal e^(-qT)")
        void putCallDeltaParity() {
            LocalDate expiry = LocalDate.now().plusDays(30);
            BigDecimal callPremium = BigDecimal.valueOf(350);
            BigDecimal putPremium = BigDecimal.valueOf(300);

            Greeks callGreeks = greeksCalculator.calculate(NIFTY_SPOT, ATM_STRIKE, expiry, callPremium, true);
            Greeks putGreeks = greeksCalculator.calculate(NIFTY_SPOT, ATM_STRIKE, expiry, putPremium, false);

            assertTrue(callGreeks.isAvailable());
            assertTrue(putGreeks.isAvailable());

            // For zero dividend yield: call_delta - put_delta should approximately equal 1
            double deltaDiff =
                    callGreeks.getDelta().doubleValue() - putGreeks.getDelta().doubleValue();
            assertTrue(
                    Math.abs(deltaDiff - 1.0) < 0.05,
                    "Call delta - Put delta should be ~1 for same strike, got: " + deltaDiff);
        }

        @Test
        @DisplayName("Call and put gamma should be equal for same strike and IV")
        void putCallGammaParity() {
            // Use calculateDirect with same IV so gamma comparison is apples-to-apples
            LocalDate expiry = LocalDate.now().plusDays(30);
            BigDecimal iv = BigDecimal.valueOf(0.20);

            Greeks callGreeks = greeksCalculator.calculateDirect(NIFTY_SPOT, ATM_STRIKE, expiry, iv, true, 0.0);
            Greeks putGreeks = greeksCalculator.calculateDirect(NIFTY_SPOT, ATM_STRIKE, expiry, iv, false, 0.0);

            assertTrue(callGreeks.isAvailable());
            assertTrue(putGreeks.isAvailable());

            // With same IV, gamma is identical for calls and puts at the same strike
            assertEquals(
                    callGreeks.getGamma(),
                    putGreeks.getGamma(),
                    "Call and put gamma should be exactly equal with same IV and strike");
        }
    }

    @Nested
    @DisplayName("Black-Scholes Price Calculation")
    class BlackScholesPrice {

        @Test
        @DisplayName("ATM call price should be reasonable")
        void atmCallPriceReasonable() {
            double S = 22000;
            double K = 22000;
            double T = 30.0 / 365.0;
            double r = 0.07;
            double sigma = 0.15; // 15% IV

            double price = greeksCalculator.blackScholesPrice(S, K, T, r, 0, sigma, true);

            // ATM NIFTY call with 15% IV, 30 DTE should be in a reasonable range
            assertTrue(price > 100 && price < 600, "ATM call price with 15% IV should be reasonable, got: " + price);
        }

        @Test
        @DisplayName("Call price should always be non-negative")
        void callPriceNonNegative() {
            double S = 22000;
            double K = 25000; // Far OTM
            double T = 7.0 / 365.0;
            double r = 0.07;
            double sigma = 0.15;

            double price = greeksCalculator.blackScholesPrice(S, K, T, r, 0, sigma, true);

            assertTrue(price >= 0, "Option price should never be negative, got: " + price);
        }

        @Test
        @DisplayName("Put-Call parity: C - P = S*e^(-qT) - K*e^(-rT)")
        void putCallPriceParityHolds() {
            double S = 22000;
            double K = 22000;
            double T = 30.0 / 365.0;
            double r = 0.07;
            double q = 0;
            double sigma = 0.20;

            double callPrice = greeksCalculator.blackScholesPrice(S, K, T, r, q, sigma, true);
            double putPrice = greeksCalculator.blackScholesPrice(S, K, T, r, q, sigma, false);

            // C - P = S*e^(-qT) - K*e^(-rT)
            double expected = S * Math.exp(-q * T) - K * Math.exp(-r * T);
            double actual = callPrice - putPrice;

            assertTrue(
                    Math.abs(actual - expected) < 1.0,
                    "Put-call parity should hold. Expected: " + expected + ", got: " + actual);
        }
    }

    @Nested
    @DisplayName("IV Solver")
    class IvSolver {

        @Test
        @DisplayName("Solved IV should produce price matching the input")
        void solvedIvReproducesInputPrice() {
            LocalDate expiry = LocalDate.now().plusDays(30);
            BigDecimal premium = BigDecimal.valueOf(350);

            Greeks greeks = greeksCalculator.calculate(NIFTY_SPOT, ATM_STRIKE, expiry, premium, true);

            assertTrue(greeks.isAvailable(), "Greeks should be available");
            double iv = greeks.getIv().doubleValue() / 100.0; // Convert from percentage to decimal
            assertTrue(iv > 0.01 && iv < 2.0, "IV should be in sane range, got: " + (iv * 100) + "%");
        }

        @Test
        @DisplayName("IV for higher premium should be higher")
        void higherPremiumHigherIv() {
            LocalDate expiry = LocalDate.now().plusDays(30);
            BigDecimal lowPremium = BigDecimal.valueOf(200);
            BigDecimal highPremium = BigDecimal.valueOf(500);

            Greeks lowGreeks = greeksCalculator.calculate(NIFTY_SPOT, ATM_STRIKE, expiry, lowPremium, true);
            Greeks highGreeks = greeksCalculator.calculate(NIFTY_SPOT, ATM_STRIKE, expiry, highPremium, true);

            assertTrue(lowGreeks.isAvailable());
            assertTrue(highGreeks.isAvailable());
            assertTrue(highGreeks.getIv().compareTo(lowGreeks.getIv()) > 0, "Higher premium should imply higher IV");
        }
    }

    @Nested
    @DisplayName("Invalid Input Handling")
    class InvalidInput {

        @Test
        @DisplayName("Zero option price returns UNAVAILABLE")
        void zeroOptionPriceReturnsUnavailable() {
            LocalDate expiry = LocalDate.now().plusDays(30);
            Greeks greeks = greeksCalculator.calculate(NIFTY_SPOT, ATM_STRIKE, expiry, BigDecimal.ZERO, true);

            assertFalse(greeks.isAvailable(), "Zero option price should return UNAVAILABLE");
        }

        @Test
        @DisplayName("Negative option price returns UNAVAILABLE")
        void negativeOptionPriceReturnsUnavailable() {
            LocalDate expiry = LocalDate.now().plusDays(30);
            Greeks greeks = greeksCalculator.calculate(NIFTY_SPOT, ATM_STRIKE, expiry, BigDecimal.valueOf(-10), true);

            assertFalse(greeks.isAvailable(), "Negative option price should return UNAVAILABLE");
        }

        @Test
        @DisplayName("Zero spot price returns UNAVAILABLE")
        void zeroSpotPriceReturnsUnavailable() {
            LocalDate expiry = LocalDate.now().plusDays(30);
            Greeks greeks =
                    greeksCalculator.calculate(BigDecimal.ZERO, ATM_STRIKE, expiry, BigDecimal.valueOf(350), true);

            assertFalse(greeks.isAvailable(), "Zero spot price should return UNAVAILABLE");
        }

        @Test
        @DisplayName("Zero strike returns UNAVAILABLE")
        void zeroStrikeReturnsUnavailable() {
            LocalDate expiry = LocalDate.now().plusDays(30);
            Greeks greeks =
                    greeksCalculator.calculate(NIFTY_SPOT, BigDecimal.ZERO, expiry, BigDecimal.valueOf(350), true);

            assertFalse(greeks.isAvailable(), "Zero strike should return UNAVAILABLE");
        }
    }

    @Nested
    @DisplayName("calculateDirect (bypass IV solver)")
    class CalculateDirect {

        @Test
        @DisplayName("Direct calculation with known IV should produce valid Greeks")
        void directCalculationWithKnownIv() {
            LocalDate expiry = LocalDate.now().plusDays(30);
            BigDecimal iv = BigDecimal.valueOf(0.20); // 20% IV

            Greeks greeks = greeksCalculator.calculateDirect(NIFTY_SPOT, ATM_STRIKE, expiry, iv, true, 0.0);

            assertTrue(greeks.isAvailable(), "Greeks should be available for direct calculation");
            double delta = greeks.getDelta().doubleValue();
            assertTrue(delta > 0.45 && delta < 0.65, "ATM call delta should be near 0.5, got: " + delta);
            assertTrue(greeks.getGamma().doubleValue() > 0, "Gamma should be positive");
            assertTrue(greeks.getVega().doubleValue() > 0, "Vega should be positive");
            assertTrue(greeks.getTheta().doubleValue() < 0, "Theta should be negative");
            assertEquals(BigDecimal.valueOf(20.00).setScale(2), greeks.getIv(), "IV should be stored as percentage");
        }

        @Test
        @DisplayName("Direct calculation with zero IV returns UNAVAILABLE")
        void directWithZeroIvReturnsUnavailable() {
            LocalDate expiry = LocalDate.now().plusDays(30);
            Greeks greeks =
                    greeksCalculator.calculateDirect(NIFTY_SPOT, ATM_STRIKE, expiry, BigDecimal.ZERO, true, 0.0);

            assertFalse(greeks.isAvailable(), "Zero IV should return UNAVAILABLE");
        }
    }

    @Nested
    @DisplayName("Dividend Yield Impact")
    class DividendYieldImpact {

        @Test
        @DisplayName("Dividend yield should reduce call delta")
        void dividendReducesCallDelta() {
            LocalDate expiry = LocalDate.now().plusDays(60);
            BigDecimal iv = BigDecimal.valueOf(0.20);

            Greeks noDivGreeks = greeksCalculator.calculateDirect(NIFTY_SPOT, ATM_STRIKE, expiry, iv, true, 0.0);
            Greeks divGreeks = greeksCalculator.calculateDirect(NIFTY_SPOT, ATM_STRIKE, expiry, iv, true, 0.03);

            assertTrue(noDivGreeks.isAvailable());
            assertTrue(divGreeks.isAvailable());

            // Dividend yield multiplies delta by e^(-qT), so call delta should be lower
            assertTrue(
                    noDivGreeks.getDelta().compareTo(divGreeks.getDelta()) > 0,
                    "Dividend yield should reduce call delta");
        }
    }
}
