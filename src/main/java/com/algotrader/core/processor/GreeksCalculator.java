package com.algotrader.core.processor;

import com.algotrader.domain.model.Greeks;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.springframework.stereotype.Component;

/**
 * Black-Scholes Greeks calculator for European options (NSE CE/PE contracts).
 *
 * <p>Kite does NOT provide Greeks — we compute them locally using spot price, strike,
 * expiry, option LTP, and a risk-free rate. IV is solved via {@link IVCalculator}
 * (Newton-Raphson with bisection fallback), then Greeks are derived analytically
 * from d1/d2.
 *
 * <p>Key formulas:
 * <ul>
 *   <li>d1 = [ln(S/K) + (r - q + sigma^2/2) * T] / (sigma * sqrt(T))
 *   <li>d2 = d1 - sigma * sqrt(T)
 *   <li>Delta: N(d1) * e^(-qT) for calls, [N(d1) - 1] * e^(-qT) for puts
 *   <li>Gamma: n(d1) * e^(-qT) / (S * sigma * sqrt(T))
 *   <li>Theta: per-day decay (separate formulas for calls/puts)
 *   <li>Vega: S * e^(-qT) * n(d1) * sqrt(T) / 100 (per 1% IV change)
 *   <li>Rho: K * T * e^(-rT) * N(d2) / 100 for calls, -K * T * e^(-rT) * N(-d2) / 100 for puts
 * </ul>
 *
 * <p>Edge cases handled:
 * <ul>
 *   <li>Near-zero DTE: T clamped to minimum 1 minute (1/525600 year) to avoid sqrt(0)
 *   <li>Deep ITM/OTM: IV solver handles via bisection fallback in {@link IVCalculator}
 *   <li>IV sanity: values outside 1%-200% are clamped by {@link IVCalculator}
 *   <li>Dividend yield: supported for stock options (default 0 for index options)
 * </ul>
 */
@Slf4j
@Component
public class GreeksCalculator {

    // Risk-free rate: 7% (RBI repo rate, configurable in future)
    private static final double RISK_FREE_RATE = 0.07;

    // Minimum T in years: 1 minute = 1/525600 year (365.25 * 24 * 60)
    private static final double MIN_T_YEARS = 1.0 / 525600.0;

    // IST market close time on expiry day: 15:30
    private static final int MARKET_CLOSE_HOUR = 15;
    private static final int MARKET_CLOSE_MINUTE = 30;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // Reusable standard normal distribution (thread-safe in commons-math3)
    private static final NormalDistribution NORM = new NormalDistribution();

    private final IVCalculator ivCalculator;

    public GreeksCalculator(IVCalculator ivCalculator) {
        this.ivCalculator = ivCalculator;
    }

    /**
     * Calculates all Greeks for an option by first solving IV from the market price,
     * then deriving Greeks analytically. Assumes zero dividend yield (index options).
     *
     * @param spotPrice  current underlying spot price
     * @param strike     option strike price
     * @param expiry     option expiry date
     * @param optionPrice current market price (LTP) of the option
     * @param isCall     true for CE, false for PE
     * @return calculated Greeks, or {@link Greeks#UNAVAILABLE} if IV solver fails
     */
    public Greeks calculate(
            BigDecimal spotPrice, BigDecimal strike, LocalDate expiry, BigDecimal optionPrice, boolean isCall) {
        return calculate(spotPrice, strike, expiry, optionPrice, isCall, 0.0);
    }

    /**
     * Calculates all Greeks with an explicit dividend yield (for stock options).
     *
     * @param spotPrice     current underlying spot price
     * @param strike        option strike price
     * @param expiry        option expiry date
     * @param optionPrice   current market price (LTP) of the option
     * @param isCall        true for CE, false for PE
     * @param dividendYield continuous dividend yield as a decimal (e.g., 0.02 for 2%)
     * @return calculated Greeks, or {@link Greeks#UNAVAILABLE} if IV solver fails
     */
    public Greeks calculate(
            BigDecimal spotPrice,
            BigDecimal strike,
            LocalDate expiry,
            BigDecimal optionPrice,
            boolean isCall,
            double dividendYield) {

        double S = spotPrice.doubleValue();
        double K = strike.doubleValue();
        double T = getTimeToExpiry(expiry);
        double r = RISK_FREE_RATE;
        double q = dividendYield;
        double price = optionPrice.doubleValue();

        // Guard: zero or negative price can't produce valid IV
        if (price <= 0 || S <= 0 || K <= 0) {
            return Greeks.UNAVAILABLE;
        }

        // Delegate IV solving to IVCalculator
        double iv = ivCalculator.solve(S, K, T, r, q, price, isCall);

        if (iv < 0) {
            return Greeks.UNAVAILABLE;
        }

        return calculateFromIV(S, K, T, r, q, iv, isCall);
    }

    /**
     * Calculates Greeks directly from a known IV value, bypassing the IV solver.
     * Used by scalping strategies that maintain their own IV estimates, and for
     * option chain display where IV is pre-solved.
     *
     * @param spotPrice     current underlying spot price
     * @param strike        option strike price
     * @param expiry        option expiry date
     * @param iv            implied volatility as a decimal (e.g., 0.16 = 16%)
     * @param isCall        true for CE, false for PE
     * @param dividendYield continuous dividend yield as a decimal
     * @return calculated Greeks
     */
    public Greeks calculateDirect(
            BigDecimal spotPrice,
            BigDecimal strike,
            LocalDate expiry,
            BigDecimal iv,
            boolean isCall,
            double dividendYield) {

        double S = spotPrice.doubleValue();
        double K = strike.doubleValue();
        double T = getTimeToExpiry(expiry);
        double sigma = iv.doubleValue();

        if (S <= 0 || K <= 0 || sigma <= 0) {
            return Greeks.UNAVAILABLE;
        }

        return calculateFromIV(S, K, T, RISK_FREE_RATE, dividendYield, sigma, isCall);
    }

    /**
     * Calculates the theoretical Black-Scholes price for a European option.
     * Delegates to {@link IVCalculator#blackScholesPrice} for consistency.
     */
    public double blackScholesPrice(double S, double K, double T, double r, double q, double sigma, boolean isCall) {
        return ivCalculator.blackScholesPrice(S, K, T, r, q, sigma, isCall);
    }

    /**
     * Core Greeks calculation from known parameters including IV.
     */
    private Greeks calculateFromIV(double S, double K, double T, double r, double q, double iv, boolean isCall) {
        double sqrtT = Math.sqrt(T);
        double d1 = (Math.log(S / K) + (r - q + iv * iv / 2.0) * T) / (iv * sqrtT);
        double d2 = d1 - iv * sqrtT;

        double nd1 = NORM.density(d1); // PDF at d1
        double Nd1 = NORM.cumulativeProbability(d1); // CDF at d1
        double Nd2 = NORM.cumulativeProbability(d2); // CDF at d2

        double expQT = Math.exp(-q * T);
        double expRT = Math.exp(-r * T);

        double delta;
        double theta;
        double rho;

        if (isCall) {
            delta = expQT * Nd1;
            // Theta for calls: [-S * e^(-qT) * n(d1) * sigma / (2*sqrt(T))
            //                    + q * S * e^(-qT) * N(d1)
            //                    - r * K * e^(-rT) * N(d2)] / 365
            theta = (-S * expQT * nd1 * iv / (2.0 * sqrtT) + q * S * expQT * Nd1 - r * K * expRT * Nd2) / 365.0;
            // Rho for calls: K * T * e^(-rT) * N(d2) / 100
            rho = K * T * expRT * Nd2 / 100.0;
        } else {
            delta = expQT * (Nd1 - 1.0);
            // Theta for puts: [-S * e^(-qT) * n(d1) * sigma / (2*sqrt(T))
            //                   - q * S * e^(-qT) * N(-d1)
            //                   + r * K * e^(-rT) * N(-d2)] / 365
            theta = (-S * expQT * nd1 * iv / (2.0 * sqrtT)
                            - q * S * expQT * NORM.cumulativeProbability(-d1)
                            + r * K * expRT * NORM.cumulativeProbability(-d2))
                    / 365.0;
            // Rho for puts: -K * T * e^(-rT) * N(-d2) / 100
            rho = -K * T * expRT * NORM.cumulativeProbability(-d2) / 100.0;
        }

        // Gamma and Vega are the same for calls and puts
        double gamma = expQT * nd1 / (S * iv * sqrtT);
        // Vega: per 1% IV change (divide by 100)
        double vega = S * expQT * nd1 * sqrtT / 100.0;

        return Greeks.builder()
                .delta(BigDecimal.valueOf(delta).setScale(4, RoundingMode.HALF_UP))
                .gamma(BigDecimal.valueOf(gamma).setScale(6, RoundingMode.HALF_UP))
                .theta(BigDecimal.valueOf(theta).setScale(2, RoundingMode.HALF_UP))
                .vega(BigDecimal.valueOf(vega).setScale(2, RoundingMode.HALF_UP))
                .rho(BigDecimal.valueOf(rho).setScale(4, RoundingMode.HALF_UP))
                .iv(BigDecimal.valueOf(iv * 100).setScale(2, RoundingMode.HALF_UP))
                .calculatedAt(LocalDateTime.now(IST))
                .build();
    }

    /**
     * Calculates time to expiry in years. Uses minutes to market close (15:30 IST)
     * on the expiry day for precision, especially on expiry day itself. Clamped to
     * a minimum of 1 minute to prevent division-by-zero in sqrt(T).
     */
    double getTimeToExpiry(LocalDate expiry) {
        LocalDateTime now = LocalDateTime.now(IST);
        // Market close on expiry day: 15:30 IST
        LocalDateTime expiryClose = expiry.atTime(MARKET_CLOSE_HOUR, MARKET_CLOSE_MINUTE);
        long minutes = ChronoUnit.MINUTES.between(now, expiryClose);
        // Clamp to minimum 1 minute to avoid division by zero
        minutes = Math.max(minutes, 1);
        // Convert to years: 525600 = 365.25 * 24 * 60
        return minutes / 525600.0;
    }
}
