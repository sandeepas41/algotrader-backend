package com.algotrader.unit.core.processor;

import static org.junit.jupiter.api.Assertions.*;

import com.algotrader.core.processor.IVCalculator;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for IVCalculator. Validates Newton-Raphson convergence, bisection fallback,
 * IV sanity checking, and edge case handling for implied volatility solving.
 *
 * <p>Standard test parameters use NIFTY-like values: spot ~22000, risk-free rate 7%,
 * zero dividend yield.
 */
class IVCalculatorTest {

    private IVCalculator ivCalculator;

    // Standard NIFTY test parameters
    private static final double S = 22000.0;
    private static final double R = 0.07;
    private static final double Q = 0.0;

    @BeforeEach
    void setUp() {
        ivCalculator = new IVCalculator();
    }

    @Nested
    @DisplayName("Newton-Raphson Convergence")
    class NewtonRaphsonConvergence {

        @Test
        @DisplayName("ATM call converges within 10 iterations")
        void atmCallConvergesWithin10() {
            double K = 22000.0;
            double T = 30.0 / 365.0;
            double targetIV = 0.15;
            double price = ivCalculator.blackScholesPrice(S, K, T, R, Q, targetIV, true);

            int iterations = ivCalculator.convergenceIterations(S, K, T, R, Q, price, true);

            assertTrue(iterations > 0, "Should converge");
            assertTrue(iterations <= 10, "ATM call should converge within 10 iterations, took: " + iterations);
        }

        @Test
        @DisplayName("OTM call converges within 10 iterations")
        void otmCallConvergesWithin10() {
            double K = 22500.0;
            double T = 30.0 / 365.0;
            double targetIV = 0.18;
            double price = ivCalculator.blackScholesPrice(S, K, T, R, Q, targetIV, true);

            int iterations = ivCalculator.convergenceIterations(S, K, T, R, Q, price, true);

            assertTrue(iterations > 0, "Should converge");
            assertTrue(iterations <= 10, "OTM call should converge within 10 iterations, took: " + iterations);
        }

        @Test
        @DisplayName("ATM put converges within 10 iterations")
        void atmPutConvergesWithin10() {
            double K = 22000.0;
            double T = 30.0 / 365.0;
            double targetIV = 0.20;
            double price = ivCalculator.blackScholesPrice(S, K, T, R, Q, targetIV, false);

            int iterations = ivCalculator.convergenceIterations(S, K, T, R, Q, price, false);

            assertTrue(iterations > 0, "Should converge");
            assertTrue(iterations <= 10, "ATM put should converge within 10 iterations, took: " + iterations);
        }

        @Test
        @DisplayName("Solved IV matches the target IV that generated the price")
        void solvedIvMatchesTarget() {
            double K = 22000.0;
            double T = 30.0 / 365.0;
            double targetIV = 0.16;
            double price = ivCalculator.blackScholesPrice(S, K, T, R, Q, targetIV, true);

            double solvedIV = ivCalculator.solve(S, K, T, R, Q, price, true);

            assertTrue(solvedIV > 0, "Should solve successfully");
            double errorPct = Math.abs(solvedIV - targetIV) / targetIV * 100;
            assertTrue(errorPct < 0.5, "Solved IV should match target within 0.5%, error: " + errorPct + "%");
        }

        @Test
        @DisplayName("Solved IV round-trips: BS(solve(BS(sigma))) == sigma")
        void solvedIvRoundTrips() {
            double K = 21500.0; // ITM call
            double T = 14.0 / 365.0;
            double targetIV = 0.22;
            double price = ivCalculator.blackScholesPrice(S, K, T, R, Q, targetIV, true);

            double solvedIV = ivCalculator.solve(S, K, T, R, Q, price, true);

            assertTrue(solvedIV > 0, "Should solve successfully");
            // Verify round-trip: price from solved IV should match original price
            double reconstructedPrice = ivCalculator.blackScholesPrice(S, K, T, R, Q, solvedIV, true);
            assertTrue(
                    Math.abs(reconstructedPrice - price) < 0.01,
                    "Round-trip price should match original. Original: " + price + ", reconstructed: "
                            + reconstructedPrice);
        }
    }

    @Nested
    @DisplayName("Bisection Fallback")
    class BisectionFallback {

        @Test
        @DisplayName("Bisection produces correct IV when NR would fail due to near-zero vega")
        void bisectionWorksForDeepOtm() {
            // Deep OTM call: strike 25000 with spot 22000, short expiry
            double K = 25000.0;
            double T = 7.0 / 365.0;
            double targetIV = 0.30;
            double price = ivCalculator.blackScholesPrice(S, K, T, R, Q, targetIV, true);

            // Even if NR fails internally, the solve() method should still return a result
            // via bisection fallback
            double solvedIV = ivCalculator.solve(S, K, T, R, Q, price, true);

            assertTrue(solvedIV > 0, "Should solve via bisection if NR fails");
            // Allow wider tolerance since bisection is less precise than NR
            double errorPct = Math.abs(solvedIV - targetIV) / targetIV * 100;
            assertTrue(errorPct < 1.0, "Bisection IV should be within 1% of target, error: " + errorPct + "%");
        }

        @Test
        @DisplayName("Bisection handles ITM puts where NR may struggle")
        void bisectionHandlesItmPut() {
            // Deep ITM put: strike 24000 with spot 22000
            double K = 24000.0;
            double T = 30.0 / 365.0;
            double targetIV = 0.20;
            double price = ivCalculator.blackScholesPrice(S, K, T, R, Q, targetIV, false);

            double solvedIV = ivCalculator.solve(S, K, T, R, Q, price, false);

            assertTrue(solvedIV > 0, "Should solve for deep ITM put");
            double errorPct = Math.abs(solvedIV - targetIV) / targetIV * 100;
            assertTrue(errorPct < 1.0, "IV should be within 1% of target, error: " + errorPct + "%");
        }
    }

    @Nested
    @DisplayName("Non-Convergence Returns Negative")
    class NonConvergence {

        @Test
        @DisplayName("Price outside achievable range returns -1")
        void priceOutsideRangeReturnsNegative() {
            double K = 22000.0;
            double T = 30.0 / 365.0;
            // Price higher than any BS price can produce (even at 500% IV)
            double impossiblePrice = 25000.0;

            double iv = ivCalculator.solve(S, K, T, R, Q, impossiblePrice, true);

            assertTrue(iv < 0, "Impossible price should return negative sentinel");
        }

        @Test
        @DisplayName("Zero price returns -1")
        void zeroPriceReturnsNegative() {
            double iv = ivCalculator.solve(S, 22000, 30.0 / 365.0, R, Q, 0, true);
            assertTrue(iv < 0, "Zero price should return -1");
        }

        @Test
        @DisplayName("Negative price returns -1")
        void negativePriceReturnsNegative() {
            double iv = ivCalculator.solve(S, 22000, 30.0 / 365.0, R, Q, -50, true);
            assertTrue(iv < 0, "Negative price should return -1");
        }

        @Test
        @DisplayName("Zero spot returns -1")
        void zeroSpotReturnsNegative() {
            double iv = ivCalculator.solve(0, 22000, 30.0 / 365.0, R, Q, 350, true);
            assertTrue(iv < 0, "Zero spot should return -1");
        }

        @Test
        @DisplayName("Zero T returns -1")
        void zeroTReturnsNegative() {
            double iv = ivCalculator.solve(S, 22000, 0, R, Q, 350, true);
            assertTrue(iv < 0, "Zero T should return -1");
        }
    }

    @Nested
    @DisplayName("IV Sanity Check")
    class IvSanityCheck {

        @Test
        @DisplayName("IV within 1%-200% passes validation")
        void validIvPassesCheck() {
            double valid = ivCalculator.validateIV(0.15); // 15%
            assertEquals(0.15, valid, 1e-10, "Valid IV should pass through unchanged");
        }

        @Test
        @DisplayName("IV below 1% is clamped to 1%")
        void ivBelowMinIsClamped() throws Exception {
            double ivMin = getStaticDouble("IV_MIN");
            double clamped = ivCalculator.validateIV(0.005); // 0.5%
            assertEquals(ivMin, clamped, 1e-10, "IV below 1% should be clamped to 1%");
        }

        @Test
        @DisplayName("IV above 200% is clamped to 200%")
        void ivAboveMaxIsClamped() throws Exception {
            double ivMax = getStaticDouble("IV_MAX");
            double clamped = ivCalculator.validateIV(3.0); // 300%
            assertEquals(ivMax, clamped, 1e-10, "IV above 200% should be clamped to 200%");
        }

        @Test
        @DisplayName("IV at exactly 1% passes")
        void ivAtMinBoundaryPasses() {
            double valid = ivCalculator.validateIV(0.01);
            assertEquals(0.01, valid, 1e-10);
        }

        @Test
        @DisplayName("IV at exactly 200% passes")
        void ivAtMaxBoundaryPasses() {
            double valid = ivCalculator.validateIV(2.0);
            assertEquals(2.0, valid, 1e-10);
        }
    }

    @Nested
    @DisplayName("Black-Scholes Price Properties")
    class BlackScholesPriceProperties {

        @Test
        @DisplayName("Higher IV produces higher option price")
        void higherIvHigherPrice() {
            double K = 22000.0;
            double T = 30.0 / 365.0;

            double lowIvPrice = ivCalculator.blackScholesPrice(S, K, T, R, Q, 0.10, true);
            double highIvPrice = ivCalculator.blackScholesPrice(S, K, T, R, Q, 0.30, true);

            assertTrue(highIvPrice > lowIvPrice, "Higher IV should produce higher price");
        }

        @Test
        @DisplayName("Longer expiry produces higher option price")
        void longerExpiryHigherPrice() {
            double K = 22000.0;
            double sigma = 0.15;

            double shortExpPrice = ivCalculator.blackScholesPrice(S, K, 7.0 / 365.0, R, Q, sigma, true);
            double longExpPrice = ivCalculator.blackScholesPrice(S, K, 60.0 / 365.0, R, Q, sigma, true);

            assertTrue(longExpPrice > shortExpPrice, "Longer expiry should produce higher price");
        }

        @Test
        @DisplayName("IV monotonicity: solve returns different IVs for different prices")
        void ivMonotonicity() {
            double K = 22000.0;
            double T = 30.0 / 365.0;
            double lowPrice = ivCalculator.blackScholesPrice(S, K, T, R, Q, 0.12, true);
            double highPrice = ivCalculator.blackScholesPrice(S, K, T, R, Q, 0.25, true);

            double lowIV = ivCalculator.solve(S, K, T, R, Q, lowPrice, true);
            double highIV = ivCalculator.solve(S, K, T, R, Q, highPrice, true);

            assertTrue(lowIV > 0 && highIV > 0, "Both should solve");
            assertTrue(highIV > lowIV, "Higher price should yield higher IV");
        }
    }

    @Nested
    @DisplayName("Convergence Diagnostics")
    class ConvergenceDiagnostics {

        @Test
        @DisplayName("convergenceIterations returns -1 for invalid inputs")
        void invalidInputReturnsNegative() {
            int result = ivCalculator.convergenceIterations(0, 22000, 30.0 / 365.0, R, Q, 350, true);
            assertEquals(-1, result, "Invalid input should return -1");
        }

        @Test
        @DisplayName("convergenceIterations returns positive for standard case")
        void standardCaseReturnsPositive() {
            double K = 22000.0;
            double T = 30.0 / 365.0;
            double price = ivCalculator.blackScholesPrice(S, K, T, R, Q, 0.15, true);

            int iterations = ivCalculator.convergenceIterations(S, K, T, R, Q, price, true);

            assertTrue(iterations > 0, "Standard case should converge");
        }
    }

    private double getStaticDouble(String fieldName) throws Exception {
        Field field = IVCalculator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (double) field.get(null);
    }
}
