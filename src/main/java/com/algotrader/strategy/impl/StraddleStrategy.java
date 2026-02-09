package com.algotrader.strategy.impl;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.oms.OrderRequest;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.MarketSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Short straddle strategy: sells ATM Call + ATM Put on the same underlying and expiry.
 *
 * <p><b>Market view:</b> Neutral. Profits from theta decay and IV crush when the
 * underlying stays near the strike. Maximum premium collection at ATM, but unlimited
 * risk on both sides. Requires active management via delta-based shifting.
 *
 * <p><b>Entry conditions:</b>
 * <ul>
 *   <li>Within configured entry time window (e.g., 9:20-10:00 IST)</li>
 *   <li>ATM implied volatility >= configured minimum (e.g., IV >= 12%)</li>
 * </ul>
 *
 * <p><b>Exit conditions:</b>
 * <ul>
 *   <li>Premium decay target: P&L >= entryPremium * targetPercent (e.g., 50%)</li>
 *   <li>Stop loss: P&L <= -(entryPremium * stopLossMultiplier) (e.g., 1.5x)</li>
 *   <li>DTE exit: days to expiry <= minDaysToExpiry</li>
 * </ul>
 *
 * <p><b>Adjustment (delta-based shift):</b> When the absolute net position delta
 * exceeds the shift threshold (e.g., |delta| > 0.35), the straddle is closed and
 * re-entered at the new ATM strike. This effectively "chases" the underlying to
 * keep the straddle centered.
 *
 * <p><b>Legs:</b> 2 (short CE + short PE at the same ATM strike).
 *
 * <p><b>Monitoring interval:</b> 5 minutes (positional default).
 */
public class StraddleStrategy extends BaseStrategy {

    private static final Logger log = LoggerFactory.getLogger(StraddleStrategy.class);

    /** Positional strategies evaluate every 5 minutes. */
    private static final Duration MONITORING_INTERVAL = Duration.ofMinutes(5);

    private final StraddleConfig straddleConfig;

    public StraddleStrategy(String id, String name, StraddleConfig straddleConfig) {
        super(id, name, straddleConfig);
        this.straddleConfig = straddleConfig;
    }

    // ========================
    // IDENTITY
    // ========================

    @Override
    public StrategyType getType() {
        return StrategyType.STRADDLE;
    }

    @Override
    public Duration getMonitoringInterval() {
        return MONITORING_INTERVAL;
    }

    // ========================
    // ENTRY
    // ========================

    /**
     * Entry requires:
     * 1. Current time within the configured entry window
     * 2. ATM IV at or above the minimum threshold
     */
    @Override
    protected boolean shouldEnter(MarketSnapshot snapshot) {
        if (!isWithinEntryWindow()) {
            return false;
        }

        BigDecimal minIV = straddleConfig.getMinIV();
        BigDecimal atmIV = snapshot.getAtmIV();

        // If IV threshold is configured but no IV data available, skip entry
        if (minIV != null && (atmIV == null || atmIV.compareTo(minIV) < 0)) {
            return false;
        }

        return true;
    }

    /**
     * Builds 2 sell-side MARKET orders at the ATM strike: one CE, one PE.
     * Quantity is 1 lot (scaling happens in BaseStrategy.executeEntry via PositionSizer).
     */
    @Override
    protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
        BigDecimal atmStrike = roundToStrike(snapshot.getSpotPrice());

        OrderRequest sellCE = OrderRequest.builder()
                .tradingSymbol(buildTradingSymbol(atmStrike, "CE"))
                .side(OrderSide.SELL)
                .type(OrderType.MARKET)
                .quantity(1)
                .build();

        OrderRequest sellPE = OrderRequest.builder()
                .tradingSymbol(buildTradingSymbol(atmStrike, "PE"))
                .side(OrderSide.SELL)
                .type(OrderType.MARKET)
                .quantity(1)
                .build();

        return List.of(sellCE, sellPE);
    }

    // ========================
    // EXIT
    // ========================

    /**
     * Exit triggers on:
     * 1. Target profit reached (premium decay %)
     * 2. Stop loss hit (premium loss multiplier)
     * 3. DTE below minimum
     */
    @Override
    protected boolean shouldExit(MarketSnapshot snapshot) {
        // Target: P&L >= entryPremium * targetPercent
        if (isTargetReached(straddleConfig.getTargetPercent())) {
            logDecision(
                    "EXIT_REASON",
                    "Target profit reached",
                    Map.of("targetPercent", straddleConfig.getTargetPercent().toString()));
            return true;
        }

        // Stop loss: P&L <= -(entryPremium * stopLossMultiplier)
        if (isStopLossHit(straddleConfig.getStopLossMultiplier())) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss hit",
                    Map.of(
                            "stopLossMultiplier",
                            straddleConfig.getStopLossMultiplier().toString()));
            return true;
        }

        // DTE exit
        if (isDteExitTriggered(straddleConfig.getMinDaysToExpiry())) {
            logDecision(
                    "EXIT_REASON",
                    "DTE exit triggered",
                    Map.of("minDaysToExpiry", straddleConfig.getMinDaysToExpiry()));
            return true;
        }

        return false;
    }

    // ========================
    // ADJUSTMENT (delta-based shift)
    // ========================

    /**
     * Delta-based straddle shift: if the net position delta breaches the threshold,
     * close the current straddle and signal re-entry at the new ATM.
     *
     * <p>The shift works by closing the current position (initiateClose) and then
     * re-arming the strategy so it re-enters at the new ATM strike on the next
     * evaluation cycle. This is a clean close-and-reopen, not a roll of individual legs.
     */
    @Override
    protected void adjust(MarketSnapshot snapshot) {
        BigDecimal shiftThreshold = straddleConfig.getShiftDeltaThreshold();
        if (shiftThreshold == null) {
            return; // No shift configured
        }

        BigDecimal delta = calculatePositionDelta();

        if (delta.abs().compareTo(shiftThreshold) > 0) {
            logDecision(
                    "ADJUST_EVAL",
                    "Delta breach, shifting straddle",
                    Map.of(
                            "delta", delta.toString(),
                            "threshold", shiftThreshold.toString(),
                            "spotPrice", snapshot.getSpotPrice().toString()));

            // Close current positions and re-arm for new ATM entry
            initiateClose();

            recordAdjustment("STRADDLE_SHIFT");
        }
    }

    // ========================
    // MORPHING
    // ========================

    /**
     * A straddle can morph into:
     * - Strangle (widen the strikes)
     * - Iron Condor (add protective wings)
     * - Iron Butterfly (same center, add wings)
     */
    @Override
    public List<StrategyType> supportedMorphs() {
        return List.of(StrategyType.STRANGLE, StrategyType.IRON_CONDOR, StrategyType.IRON_BUTTERFLY);
    }

    // ========================
    // INTERNALS
    // ========================

    /**
     * Builds a placeholder trading symbol for the order.
     * Format: {UNDERLYING}{STRIKE}{optionType}, e.g., "NIFTY22000CE".
     * The actual Kite-compatible symbol (with expiry) is resolved by InstrumentService
     * at order execution time.
     *
     * #TODO Task 8.3: Replace with InstrumentService.resolveSymbol() for full Kite symbol
     */
    private String buildTradingSymbol(BigDecimal strike, String optionType) {
        return straddleConfig.getUnderlying() + strike.stripTrailingZeros().toPlainString() + optionType;
    }
}
