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
 * Short strangle strategy: sells OTM Call + OTM Put on the same underlying and expiry.
 *
 * <p><b>Market view:</b> Neutral/range-bound with a wider expected range than a straddle.
 * Profits from theta decay and IV crush when the underlying stays between the two short
 * strikes. Collects less premium than a straddle but has a wider breakeven range.
 * Unlimited risk on both sides.
 *
 * <p><b>Legs (2):</b>
 * <ol>
 *   <li>Sell CE at ATM + callOffset (short OTM call)</li>
 *   <li>Sell PE at ATM - putOffset (short OTM put)</li>
 * </ol>
 *
 * <p><b>Entry conditions:</b>
 * <ul>
 *   <li>Within configured entry time window</li>
 *   <li>ATM implied volatility >= configured minimum</li>
 * </ul>
 *
 * <p><b>Exit conditions:</b>
 * <ul>
 *   <li>Premium decay target: P&L >= entryPremium * targetPercent</li>
 *   <li>Stop loss: P&L <= -(entryPremium * stopLossMultiplier)</li>
 *   <li>DTE exit: days to expiry <= minDaysToExpiry</li>
 * </ul>
 *
 * <p><b>Adjustment (delta-based shift):</b> When the absolute net position delta
 * exceeds the shift threshold, the strangle is closed and re-entered at new OTM
 * strikes centered around the current ATM. This keeps the strangle balanced.
 *
 * <p><b>Monitoring interval:</b> 5 minutes (positional default).
 */
public class StrangleStrategy extends BaseStrategy {

    private static final Logger log = LoggerFactory.getLogger(StrangleStrategy.class);

    /** Positional strategies evaluate every 5 minutes. */
    private static final Duration MONITORING_INTERVAL = Duration.ofMinutes(5);

    private final StrangleConfig strangleConfig;

    public StrangleStrategy(String id, String name, StrangleConfig strangleConfig) {
        super(id, name, strangleConfig);
        this.strangleConfig = strangleConfig;
    }

    // ========================
    // IDENTITY
    // ========================

    @Override
    public StrategyType getType() {
        return StrategyType.STRANGLE;
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

        BigDecimal minIV = strangleConfig.getMinIV();
        BigDecimal atmIV = snapshot.getAtmIV();

        if (minIV != null && (atmIV == null || atmIV.compareTo(minIV) < 0)) {
            return false;
        }

        return true;
    }

    /**
     * Builds 2 sell-side MARKET orders at OTM strikes:
     * 1. Sell CE at ATM + callOffset
     * 2. Sell PE at ATM - putOffset
     */
    @Override
    protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
        BigDecimal atm = roundToStrike(snapshot.getSpotPrice());

        BigDecimal sellCallStrike = atm.add(strangleConfig.getCallOffset());
        BigDecimal sellPutStrike = atm.subtract(strangleConfig.getPutOffset());

        return List.of(
                buildOrderRequest(sellCallStrike, "CE", OrderSide.SELL),
                buildOrderRequest(sellPutStrike, "PE", OrderSide.SELL));
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
        if (isTargetReached(strangleConfig.getTargetPercent())) {
            logDecision(
                    "EXIT_REASON",
                    "Target profit reached",
                    Map.of("targetPercent", strangleConfig.getTargetPercent().toString()));
            return true;
        }

        if (isStopLossHit(strangleConfig.getStopLossMultiplier())) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss hit",
                    Map.of(
                            "stopLossMultiplier",
                            strangleConfig.getStopLossMultiplier().toString()));
            return true;
        }

        if (isDteExitTriggered(strangleConfig.getMinDaysToExpiry())) {
            logDecision(
                    "EXIT_REASON",
                    "DTE exit triggered",
                    Map.of("minDaysToExpiry", strangleConfig.getMinDaysToExpiry()));
            return true;
        }

        return false;
    }

    // ========================
    // ADJUSTMENT (delta-based shift)
    // ========================

    /**
     * Delta-based strangle shift: if the net position delta breaches the threshold,
     * close the current strangle and signal re-entry at new OTM strikes.
     *
     * <p>The shift closes the entire position and re-arms for re-entry at the
     * new ATM-relative OTM strikes on the next evaluation cycle.
     */
    @Override
    protected void adjust(MarketSnapshot snapshot) {
        BigDecimal shiftThreshold = strangleConfig.getShiftDeltaThreshold();
        if (shiftThreshold == null) {
            return;
        }

        BigDecimal delta = calculatePositionDelta();

        if (delta.abs().compareTo(shiftThreshold) > 0) {
            logDecision(
                    "ADJUST_EVAL",
                    "Delta breach, shifting strangle",
                    Map.of(
                            "delta", delta.toString(),
                            "threshold", shiftThreshold.toString(),
                            "spotPrice", snapshot.getSpotPrice().toString()));

            initiateClose();
            recordAdjustment("STRANGLE_SHIFT");
        }
    }

    // ========================
    // MORPHING
    // ========================

    /**
     * A strangle can morph into:
     * - Straddle (bring both strikes to ATM)
     * - Iron Condor (add protective wings to both sides)
     */
    @Override
    public List<StrategyType> supportedMorphs() {
        return List.of(StrategyType.STRADDLE, StrategyType.IRON_CONDOR);
    }

    // ========================
    // INTERNALS
    // ========================

    private OrderRequest buildOrderRequest(BigDecimal strike, String optionType, OrderSide side) {
        return OrderRequest.builder()
                .tradingSymbol(buildTradingSymbol(strike, optionType))
                .side(side)
                .type(OrderType.MARKET)
                .quantity(1)
                .build();
    }

    /**
     * Builds a placeholder trading symbol for the order.
     * #TODO Replace with InstrumentService.resolveSymbol() for full Kite symbol
     */
    private String buildTradingSymbol(BigDecimal strike, String optionType) {
        return strangleConfig.getUnderlying() + strike.stripTrailingZeros().toPlainString() + optionType;
    }
}
