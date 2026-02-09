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
 * Iron butterfly strategy: sell ATM straddle + buy OTM strangle for protection.
 *
 * <p><b>Market view:</b> Strongly neutral with a very tight expected range. Maximizes
 * premium collection by selling both options at ATM, with risk capped by the OTM wings.
 * Higher max profit than an iron condor but much narrower profit zone.
 *
 * <p><b>Legs (4):</b>
 * <ol>
 *   <li>Sell CE at ATM (short call)</li>
 *   <li>Buy CE at ATM + wingWidth (long call / protection)</li>
 *   <li>Sell PE at ATM (short put)</li>
 *   <li>Buy PE at ATM - wingWidth (long put / protection)</li>
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
 * <p><b>Adjustment (delta roll):</b> Same as iron condor -- roll the threatened
 * short leg when delta breaches the threshold.
 *
 * <p><b>Monitoring interval:</b> 5 minutes (positional default).
 */
public class IronButterflyStrategy extends BaseStrategy {

    private static final Logger log = LoggerFactory.getLogger(IronButterflyStrategy.class);

    private static final Duration MONITORING_INTERVAL = Duration.ofMinutes(5);

    private final IronButterflyConfig ironButterflyConfig;

    public IronButterflyStrategy(String id, String name, IronButterflyConfig ironButterflyConfig) {
        super(id, name, ironButterflyConfig);
        this.ironButterflyConfig = ironButterflyConfig;
    }

    // ========================
    // IDENTITY
    // ========================

    @Override
    public StrategyType getType() {
        return StrategyType.IRON_BUTTERFLY;
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
     * 2. ATM IV at or above the minimum entry IV threshold
     */
    @Override
    protected boolean shouldEnter(MarketSnapshot snapshot) {
        if (!isWithinEntryWindow()) {
            return false;
        }

        BigDecimal minIV = ironButterflyConfig.getMinEntryIV();
        BigDecimal atmIV = snapshot.getAtmIV();

        if (minIV != null && (atmIV == null || atmIV.compareTo(minIV) < 0)) {
            return false;
        }

        return true;
    }

    /**
     * Builds 4 legs -- ATM straddle sell + OTM strangle buy:
     * 1. Sell CE at ATM
     * 2. Buy CE at ATM + wingWidth
     * 3. Sell PE at ATM
     * 4. Buy PE at ATM - wingWidth
     */
    @Override
    protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
        BigDecimal atm = roundToStrike(snapshot.getSpotPrice());
        BigDecimal wingWidth = ironButterflyConfig.getWingWidth();

        BigDecimal buyCallStrike = atm.add(wingWidth);
        BigDecimal buyPutStrike = atm.subtract(wingWidth);

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
        if (isTargetReached(ironButterflyConfig.getTargetPercent())) {
            logDecision(
                    "EXIT_REASON",
                    "Target profit reached",
                    Map.of(
                            "targetPercent",
                            ironButterflyConfig.getTargetPercent().toString()));
            return true;
        }

        if (isStopLossHit(ironButterflyConfig.getStopLossMultiplier())) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss hit",
                    Map.of(
                            "stopLossMultiplier",
                            ironButterflyConfig.getStopLossMultiplier().toString()));
            return true;
        }

        if (isDteExitTriggered(ironButterflyConfig.getMinDaysToExpiry())) {
            logDecision(
                    "EXIT_REASON",
                    "DTE exit triggered",
                    Map.of("minDaysToExpiry", ironButterflyConfig.getMinDaysToExpiry()));
            return true;
        }

        return false;
    }

    // ========================
    // ADJUSTMENT (delta roll)
    // ========================

    /**
     * Delta-based rolling adjustment (same logic as Iron Condor):
     * - Delta too positive (> threshold): call side under pressure, roll call up
     * - Delta too negative (< -threshold): put side under pressure, roll put down
     */
    @Override
    protected void adjust(MarketSnapshot snapshot) {
        BigDecimal rollThreshold = ironButterflyConfig.getDeltaRollThreshold();
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
     * An iron butterfly can morph into:
     * - Iron Condor (widen the short strikes away from ATM)
     * - Straddle (remove wings, keep ATM short legs)
     */
    @Override
    public List<StrategyType> supportedMorphs() {
        return List.of(StrategyType.IRON_CONDOR, StrategyType.STRADDLE);
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
        return ironButterflyConfig.getUnderlying() + strike.stripTrailingZeros().toPlainString() + optionType;
    }
}
