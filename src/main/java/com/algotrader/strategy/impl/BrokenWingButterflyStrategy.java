package com.algotrader.strategy.impl;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Position;
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
 * Broken wing butterfly strategy: ATM straddle sell + asymmetric OTM protection wings.
 *
 * <p><b>Market view:</b> Neutral with a directional bias. The wider wing accepts
 * more risk on one side to potentially enter the trade for a net credit. If the
 * put wing is wider, the trader is more bullish (accepting downside risk for credit).
 * If the call wing is wider, the trader is more bearish.
 *
 * <p><b>Legs (4):</b>
 * <ol>
 *   <li>Sell CE at ATM (short call)</li>
 *   <li>Buy CE at ATM + callWingWidth (long call / narrow protection)</li>
 *   <li>Sell PE at ATM (short put)</li>
 *   <li>Buy PE at ATM - putWingWidth (long put / wide protection)</li>
 * </ol>
 *
 * <p><b>Key difference from regular Iron Butterfly:</b> callWingWidth != putWingWidth.
 * This asymmetry creates a directional bias and can turn the trade into a net credit.
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
 * <p><b>Adjustment (delta roll):</b> Same as iron butterfly/condor -- roll the
 * threatened short leg when delta breaches the threshold.
 *
 * <p><b>Monitoring interval:</b> 5 minutes (positional default).
 */
public class BrokenWingButterflyStrategy extends BaseStrategy {

    private static final Logger log = LoggerFactory.getLogger(BrokenWingButterflyStrategy.class);

    private static final Duration MONITORING_INTERVAL = Duration.ofMinutes(5);

    private final BrokenWingButterflyConfig brokenWingConfig;

    public BrokenWingButterflyStrategy(String id, String name, BrokenWingButterflyConfig brokenWingConfig) {
        super(id, name, brokenWingConfig);
        this.brokenWingConfig = brokenWingConfig;
    }

    // ========================
    // IDENTITY
    // ========================

    @Override
    public StrategyType getType() {
        // Broken wing butterfly is a variant of iron butterfly
        // Uses IRON_BUTTERFLY type since there's no separate enum value
        return StrategyType.IRON_BUTTERFLY;
    }

    @Override
    public Duration getMonitoringInterval() {
        return MONITORING_INTERVAL;
    }

    // ========================
    // ENTRY
    // ========================

    @Override
    protected boolean shouldEnter(MarketSnapshot snapshot) {
        if (!isWithinEntryWindow()) {
            return false;
        }

        BigDecimal minIV = brokenWingConfig.getMinEntryIV();
        BigDecimal atmIV = snapshot.getAtmIV();

        if (minIV != null && (atmIV == null || atmIV.compareTo(minIV) < 0)) {
            return false;
        }

        return true;
    }

    /**
     * Builds 4 legs -- ATM straddle sell + asymmetric OTM wings:
     * 1. Sell CE at ATM
     * 2. Buy CE at ATM + callWingWidth (narrow wing)
     * 3. Sell PE at ATM
     * 4. Buy PE at ATM - putWingWidth (wide wing)
     */
    @Override
    protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
        BigDecimal atm = roundToStrike(snapshot.getSpotPrice());

        BigDecimal buyCallStrike = atm.add(brokenWingConfig.getCallWingWidth());
        BigDecimal buyPutStrike = atm.subtract(brokenWingConfig.getPutWingWidth());

        return List.of(
                buildOrderRequest(atm, "CE", OrderSide.SELL),
                buildOrderRequest(buyCallStrike, "CE", OrderSide.BUY),
                buildOrderRequest(atm, "PE", OrderSide.SELL),
                buildOrderRequest(buyPutStrike, "PE", OrderSide.BUY));
    }

    // ========================
    // EXIT
    // ========================

    @Override
    protected boolean shouldExit(MarketSnapshot snapshot) {
        if (isTargetReached(brokenWingConfig.getTargetPercent())) {
            logDecision(
                    "EXIT_REASON",
                    "Target profit reached",
                    Map.of("targetPercent", brokenWingConfig.getTargetPercent().toString()));
            return true;
        }

        if (isStopLossHit(brokenWingConfig.getStopLossMultiplier())) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss hit",
                    Map.of(
                            "stopLossMultiplier",
                            brokenWingConfig.getStopLossMultiplier().toString()));
            return true;
        }

        if (isDteExitTriggered(brokenWingConfig.getMinDaysToExpiry())) {
            logDecision(
                    "EXIT_REASON",
                    "DTE exit triggered",
                    Map.of("minDaysToExpiry", brokenWingConfig.getMinDaysToExpiry()));
            return true;
        }

        return false;
    }

    // ========================
    // ADJUSTMENT (delta roll)
    // ========================

    @Override
    protected void adjust(MarketSnapshot snapshot) {
        BigDecimal rollThreshold = brokenWingConfig.getDeltaRollThreshold();
        if (rollThreshold == null) {
            return;
        }

        BigDecimal delta = calculatePositionDelta();

        if (delta.compareTo(rollThreshold) > 0) {
            Position shortCall = getShortCall();
            if (shortCall != null) {
                logDecision(
                        "ADJUST_EVAL",
                        "Delta too positive, rolling call side up",
                        Map.of(
                                "delta", delta.toString(),
                                "threshold", rollThreshold.toString(),
                                "shortCallSymbol", shortCall.getTradingSymbol()));

                // #TODO Execute roll via multiLegExecutor
                recordAdjustment("ROLL_CALL_UP");
            }
        }

        if (delta.compareTo(rollThreshold.negate()) < 0) {
            Position shortPut = getShortPut();
            if (shortPut != null) {
                logDecision(
                        "ADJUST_EVAL",
                        "Delta too negative, rolling put side down",
                        Map.of(
                                "delta", delta.toString(),
                                "threshold", rollThreshold.negate().toString(),
                                "shortPutSymbol", shortPut.getTradingSymbol()));

                // #TODO Execute roll via multiLegExecutor
                recordAdjustment("ROLL_PUT_DOWN");
            }
        }
    }

    // ========================
    // MORPHING
    // ========================

    /**
     * A broken wing butterfly can morph into:
     * - Iron Condor (widen the short strikes away from ATM)
     * - Iron Butterfly (equalize wing widths)
     */
    @Override
    public List<StrategyType> supportedMorphs() {
        return List.of(StrategyType.IRON_CONDOR, StrategyType.IRON_BUTTERFLY);
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
     * #TODO Replace with InstrumentService.resolveSymbol() for full Kite symbol
     */
    private String buildTradingSymbol(BigDecimal strike, String optionType) {
        return brokenWingConfig.getUnderlying() + strike.stripTrailingZeros().toPlainString() + optionType;
    }
}
