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
 * Iron condor strategy: sells OTM Call + OTM Put, buys further OTM Call + Put for protection.
 *
 * <p><b>Market view:</b> Neutral/range-bound. Profits from theta decay and IV crush when
 * the underlying stays between the two short strikes. Maximum profit = net premium collected.
 * Maximum loss = wingWidth - net premium (capped by the long wings).
 *
 * <p><b>Legs (4):</b>
 * <ol>
 *   <li>Sell CE at ATM + callOffset (short call)</li>
 *   <li>Buy CE at short call + wingWidth (long call / protection)</li>
 *   <li>Sell PE at ATM - putOffset (short put)</li>
 *   <li>Buy PE at short put - wingWidth (long put / protection)</li>
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
 * <p><b>Adjustment (delta roll):</b> When the net position delta breaches the threshold:
 * <ul>
 *   <li>Delta too positive (call side under pressure) => log roll-up signal for short call</li>
 *   <li>Delta too negative (put side under pressure) => log roll-down signal for short put</li>
 * </ul>
 * Actual roll execution via MultiLegExecutor is deferred to Phase 7 integration.
 *
 * <p><b>Monitoring interval:</b> 5 minutes (positional default).
 */
public class IronCondorStrategy extends BaseStrategy {

    private static final Logger log = LoggerFactory.getLogger(IronCondorStrategy.class);

    /** Positional strategies evaluate every 5 minutes. */
    private static final Duration MONITORING_INTERVAL = Duration.ofMinutes(5);

    private final IronCondorConfig ironCondorConfig;

    public IronCondorStrategy(String id, String name, IronCondorConfig ironCondorConfig) {
        super(id, name, ironCondorConfig);
        this.ironCondorConfig = ironCondorConfig;
    }

    // ========================
    // IDENTITY
    // ========================

    @Override
    public StrategyType getType() {
        return StrategyType.IRON_CONDOR;
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

        BigDecimal minIV = ironCondorConfig.getMinEntryIV();
        BigDecimal atmIV = snapshot.getAtmIV();

        if (minIV != null && (atmIV == null || atmIV.compareTo(minIV) < 0)) {
            return false;
        }

        return true;
    }

    /**
     * Builds 4 legs at calculated strikes:
     * 1. Sell CE at ATM + callOffset
     * 2. Buy CE at sellCall + wingWidth
     * 3. Sell PE at ATM - putOffset
     * 4. Buy PE at sellPut - wingWidth
     */
    @Override
    protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
        BigDecimal atm = roundToStrike(snapshot.getSpotPrice());

        BigDecimal sellCallStrike = atm.add(ironCondorConfig.getCallOffset());
        BigDecimal buyCallStrike = sellCallStrike.add(ironCondorConfig.getWingWidth());
        BigDecimal sellPutStrike = atm.subtract(ironCondorConfig.getPutOffset());
        BigDecimal buyPutStrike = sellPutStrike.subtract(ironCondorConfig.getWingWidth());

        return List.of(
                buildOrderRequest(sellCallStrike, "CE", OrderSide.SELL),
                buildOrderRequest(buyCallStrike, "CE", OrderSide.BUY),
                buildOrderRequest(sellPutStrike, "PE", OrderSide.SELL),
                buildOrderRequest(buyPutStrike, "PE", OrderSide.BUY));
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
        if (isTargetReached(ironCondorConfig.getTargetPercent())) {
            logDecision(
                    "EXIT_REASON",
                    "Target profit reached",
                    Map.of("targetPercent", ironCondorConfig.getTargetPercent().toString()));
            return true;
        }

        if (isStopLossHit(ironCondorConfig.getStopLossMultiplier())) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss hit",
                    Map.of(
                            "stopLossMultiplier",
                            ironCondorConfig.getStopLossMultiplier().toString()));
            return true;
        }

        if (isDteExitTriggered(ironCondorConfig.getMinDaysToExpiry())) {
            logDecision(
                    "EXIT_REASON",
                    "DTE exit triggered",
                    Map.of("minDaysToExpiry", ironCondorConfig.getMinDaysToExpiry()));
            return true;
        }

        return false;
    }

    // ========================
    // ADJUSTMENT (delta roll)
    // ========================

    /**
     * Delta-based rolling adjustment:
     * - Delta too positive (> threshold): call side is under pressure, signal roll-up
     * - Delta too negative (< -threshold): put side is under pressure, signal roll-down
     *
     * <p>The actual roll execution (close old leg, open new leg) is deferred to Phase 7
     * when the full multi-leg executor integration is complete. For now, the adjustment
     * is logged and recorded for cooldown tracking.
     */
    @Override
    protected void adjust(MarketSnapshot snapshot) {
        BigDecimal rollThreshold = ironCondorConfig.getDeltaRollThreshold();
        if (rollThreshold == null) {
            return;
        }

        BigDecimal delta = calculatePositionDelta();

        // Delta too positive: call side under pressure, roll call up
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

                // #TODO Phase 7: Execute roll via multiLegExecutor
                //   closeOrder(shortCall) + openOrder(shortCall.strike + strikeInterval, CE, SELL)
                recordAdjustment("ROLL_CALL_UP");
            }
        }

        // Delta too negative: put side under pressure, roll put down
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

                // #TODO Phase 7: Execute roll via multiLegExecutor
                //   closeOrder(shortPut) + openOrder(shortPut.strike - strikeInterval, PE, SELL)
                recordAdjustment("ROLL_PUT_DOWN");
            }
        }
    }

    // ========================
    // MORPHING
    // ========================

    /**
     * An iron condor can morph into:
     * - Iron Butterfly (bring short strikes to ATM)
     * - Strangle (remove wings, keep short OTM legs)
     */
    @Override
    public List<StrategyType> supportedMorphs() {
        return List.of(StrategyType.IRON_BUTTERFLY, StrategyType.STRANGLE);
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
     * #TODO Task 8.3: Replace with InstrumentService.resolveSymbol() for full Kite symbol
     */
    private String buildTradingSymbol(BigDecimal strike, String optionType) {
        return ironCondorConfig.getUnderlying() + strike.stripTrailingZeros().toPlainString() + optionType;
    }
}
