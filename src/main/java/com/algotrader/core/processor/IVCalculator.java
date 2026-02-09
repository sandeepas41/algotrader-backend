package com.algotrader.core.processor;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.springframework.stereotype.Component;

/**
 * Newton-Raphson implied volatility solver with bisection fallback for European options.
 *
 * <p>Solves IV from the Black-Scholes pricing equation by inverting the price function.
 * Newton-Raphson uses vega (dC/d-sigma) as the derivative for fast convergence
 * (~3-5 iterations for standard ATM cases). For deep ITM/OTM options where vega is
 * near-zero and Newton-Raphson oscillates, a bisection fallback guarantees convergence
 * at the cost of more iterations.
 *
 * <p>IV sanity: values outside [1%, 200%] are clamped. Truly unsolvable cases
 * (price outside achievable BS range, or price <= 0) return a negative sentinel (-1)
 * which signals the caller to return {@link com.algotrader.domain.model.Greeks#UNAVAILABLE}.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @see GreeksCalculator which uses this class to compute Greeks from market prices
 */
@Slf4j
@Component
public class IVCalculator {

    // Newton-Raphson parameters
    private static final double NR_INITIAL_GUESS = 0.25; // 25%
    private static final double NR_TOLERANCE = 0.0001;
    private static final int NR_MAX_ITERATIONS = 100;

    // Bisection fallback parameters
    private static final double BISECTION_LOWER = 0.001;
    private static final double BISECTION_UPPER = 5.0;
    private static final int BISECTION_MAX_ITERATIONS = 200;

    // IV sanity bounds (as decimals, not percentages)
    static final double IV_MIN = 0.01; // 1%
    static final double IV_MAX = 2.0; // 200%

    // Reusable standard normal distribution (thread-safe in commons-math3)
    private static final NormalDistribution NORM = new NormalDistribution();

    /**
     * Solves implied volatility from the market price using Newton-Raphson with bisection fallback.
     *
     * <p>The solver:
     * <ol>
     *   <li>Tries Newton-Raphson (fast, quadratic convergence) with vega as derivative
     *   <li>Falls back to bisection if NR doesn't converge (deep ITM/OTM, near-zero vega)
     *   <li>Validates the result against sanity bounds [1%, 200%]
     * </ol>
     *
     * @param S      spot price
     * @param K      strike price
     * @param T      time to expiry in years (must be > 0)
     * @param r      risk-free rate as a decimal (e.g., 0.07)
     * @param q      dividend yield as a decimal (e.g., 0.02, or 0 for indices)
     * @param price  observed market price of the option
     * @param isCall true for call, false for put
     * @return implied volatility as a decimal (e.g., 0.16 = 16%), or -1 if unsolvable
     */
    public double solve(double S, double K, double T, double r, double q, double price, boolean isCall) {
        if (price <= 0 || S <= 0 || K <= 0 || T <= 0) {
            return -1;
        }

        double iv = solveRaw(S, K, T, r, q, price, isCall);

        if (iv < 0) {
            return -1;
        }

        return validateIV(iv);
    }

    /**
     * Solves IV without validation. Used internally by {@link #solve} and available
     * for callers that want to apply their own validation logic.
     *
     * @return raw IV as a decimal, or -1 if the solver fails to converge
     */
    double solveRaw(double S, double K, double T, double r, double q, double price, boolean isCall) {
        // Try Newton-Raphson first (fast convergence for well-behaved cases)
        Double iv = tryNewtonRaphson(S, K, T, r, q, price, isCall);
        if (iv != null) {
            return iv;
        }

        // Fallback to bisection for convergence-resistant cases (deep ITM/OTM)
        // #TODO Log DecisionEvent when falling back from NR to bisection (Task 8.1: DecisionLogger)
        log.debug(
                "Newton-Raphson did not converge for S={}, K={}, T={}, price={}, isCall={}, falling back to bisection",
                S,
                K,
                T,
                price,
                isCall);
        return bisectionMethod(S, K, T, r, q, price, isCall);
    }

    /**
     * Returns the number of Newton-Raphson iterations needed to converge, or -1 if it
     * doesn't converge. Used for monitoring and diagnostics — in production, most standard
     * options converge within 3-10 iterations.
     */
    public int convergenceIterations(double S, double K, double T, double r, double q, double price, boolean isCall) {
        if (price <= 0 || S <= 0 || K <= 0 || T <= 0) {
            return -1;
        }

        double sigma = NR_INITIAL_GUESS;

        for (int i = 0; i < NR_MAX_ITERATIONS; i++) {
            double theoreticalPrice = blackScholesPrice(S, K, T, r, q, sigma, isCall);
            double sqrtT = Math.sqrt(T);
            double d1 = (Math.log(S / K) + (r - q + sigma * sigma / 2.0) * T) / (sigma * sqrtT);
            double vega = S * Math.exp(-q * T) * NORM.density(d1) * sqrtT;

            double diff = theoreticalPrice - price;
            if (Math.abs(diff) < NR_TOLERANCE) {
                return i + 1; // 1-indexed iteration count
            }

            if (Math.abs(vega) < 1e-10) {
                return -1; // Would need bisection
            }

            sigma = sigma - diff / vega;
            if (sigma <= 0.001) sigma = 0.001;
            if (sigma > 5.0) sigma = 5.0;
        }

        return -1; // Did not converge
    }

    /**
     * Newton-Raphson IV solver. Returns null if it doesn't converge within max iterations.
     * Uses vega as the derivative for the Newton update step: sigma_new = sigma - (price_error / vega).
     */
    private Double tryNewtonRaphson(double S, double K, double T, double r, double q, double price, boolean isCall) {
        double sigma = NR_INITIAL_GUESS;

        for (int i = 0; i < NR_MAX_ITERATIONS; i++) {
            double theoreticalPrice = blackScholesPrice(S, K, T, r, q, sigma, isCall);

            // Vega (un-scaled, not divided by 100) for Newton step
            double sqrtT = Math.sqrt(T);
            double d1 = (Math.log(S / K) + (r - q + sigma * sigma / 2.0) * T) / (sigma * sqrtT);
            double vega = S * Math.exp(-q * T) * NORM.density(d1) * sqrtT;

            double diff = theoreticalPrice - price;
            if (Math.abs(diff) < NR_TOLERANCE) {
                return sigma;
            }

            // Avoid division by near-zero vega (deep ITM/OTM)
            if (Math.abs(vega) < 1e-10) {
                return null; // Fall back to bisection
            }

            sigma = sigma - diff / vega;

            // Clamp to prevent negative or absurd values during iteration
            if (sigma <= 0.001) {
                sigma = 0.001;
            }
            if (sigma > 5.0) {
                sigma = 5.0;
            }
        }

        return null; // Did not converge
    }

    /**
     * Bisection method IV solver. Slower but guaranteed to converge if the price
     * lies within the range defined by [lower_sigma, upper_sigma]. Returns -1 if
     * the target price is outside the achievable range.
     */
    private double bisectionMethod(double S, double K, double T, double r, double q, double price, boolean isCall) {
        double lower = BISECTION_LOWER;
        double upper = BISECTION_UPPER;

        // Verify the target price is bracketed
        double lowerPrice = blackScholesPrice(S, K, T, r, q, lower, isCall);
        double upperPrice = blackScholesPrice(S, K, T, r, q, upper, isCall);

        if (price < lowerPrice || price > upperPrice) {
            // Price outside achievable range — IV can't be solved
            log.debug(
                    "Option price {} outside bisection range [{}, {}], returning UNAVAILABLE",
                    price,
                    lowerPrice,
                    upperPrice);
            return -1;
        }

        for (int i = 0; i < BISECTION_MAX_ITERATIONS; i++) {
            double mid = (lower + upper) / 2.0;
            double midPrice = blackScholesPrice(S, K, T, r, q, mid, isCall);

            if (Math.abs(midPrice - price) < NR_TOLERANCE) {
                return mid;
            }

            if (midPrice > price) {
                upper = mid;
            } else {
                lower = mid;
            }
        }

        // Best approximation after max iterations
        return (lower + upper) / 2.0;
    }

    /**
     * Validates that the calculated IV falls within a sane range (1% - 200%).
     * Clamps to the nearest bound if outside range. This prevents erroneous Greeks
     * from triggering false strategy adjustments.
     */
    public double validateIV(double iv) {
        if (iv < IV_MIN || iv > IV_MAX) {
            log.warn("Suspect IV calculated: {}%. Clamping to [{}%, {}%].", iv * 100, IV_MIN * 100, IV_MAX * 100);
            return Math.max(IV_MIN, Math.min(iv, IV_MAX));
        }
        return iv;
    }

    /**
     * Calculates the theoretical Black-Scholes price for a European option.
     * Used by the IV solver internally and by GreeksCalculator and OptionChainService.
     */
    public double blackScholesPrice(double S, double K, double T, double r, double q, double sigma, boolean isCall) {
        double sqrtT = Math.sqrt(T);
        double d1 = (Math.log(S / K) + (r - q + sigma * sigma / 2.0) * T) / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;

        if (isCall) {
            return S * Math.exp(-q * T) * NORM.cumulativeProbability(d1)
                    - K * Math.exp(-r * T) * NORM.cumulativeProbability(d2);
        } else {
            return K * Math.exp(-r * T) * NORM.cumulativeProbability(-d2)
                    - S * Math.exp(-q * T) * NORM.cumulativeProbability(-d1);
        }
    }
}
