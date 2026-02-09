package com.algotrader.strategy.impl;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.oms.OrderRequest;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.base.MarketSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-leg naked option strategy supporting four StrategyTypes:
 * CE_BUY, CE_SELL, PE_BUY, PE_SELL.
 *
 * <p><b>Market view:</b> Directional. CE_BUY profits from upside, PE_BUY from downside.
 * CE_SELL/PE_SELL are premium-collection plays benefiting from time decay.
 *
 * <p><b>Dual operating modes:</b>
 * <ul>
 *   <li><b>Positional</b> (scalpingMode=false): 5-min evaluation, %-based exits
 *       (targetPercent, stopLossMultiplier, minDaysToExpiry)</li>
 *   <li><b>Scalping</b> (scalpingMode=true): tick-level evaluation, point-based exits
 *       (targetPoints, stopLossPoints, maxHoldDuration)</li>
 * </ul>
 *
 * <p><b>Legs:</b> 1 (single BUY or SELL at configured strike).
 *
 * <p><b>Adjustments:</b> None. Simple directional plays exit on target/SL.
 *
 * <p><b>Morphing:</b> None.
 */
public class NakedOptionStrategy extends BaseStrategy {

    private static final Logger log = LoggerFactory.getLogger(NakedOptionStrategy.class);

    /** Positional mode evaluates every 5 minutes. */
    private static final Duration POSITIONAL_INTERVAL = Duration.ofMinutes(5);

    /** Scalping mode uses a tighter 2-second stale data threshold. */
    private static final Duration SCALPING_STALE_THRESHOLD = Duration.ofSeconds(2);

    private final StrategyType type;
    private final NakedOptionConfig nakedOptionConfig;

    /** Derived from StrategyType: "CE" or "PE". */
    private final String optionType;

    /** Derived from StrategyType: BUY or SELL. */
    private final OrderSide side;

    public NakedOptionStrategy(String id, String name, StrategyType type, NakedOptionConfig nakedOptionConfig) {
        super(id, name, nakedOptionConfig);
        this.type = type;
        this.nakedOptionConfig = nakedOptionConfig;

        // Derive optionType and side from the StrategyType
        this.optionType = deriveOptionType(type);
        this.side = deriveSide(type);
    }

    // ========================
    // IDENTITY
    // ========================

    @Override
    public StrategyType getType() {
        return type;
    }

    /** Tick-level in scalping mode; 5-min in positional mode. */
    @Override
    public Duration getMonitoringInterval() {
        return nakedOptionConfig.isScalpingMode() ? Duration.ZERO : POSITIONAL_INTERVAL;
    }

    /** 2s stale threshold in scalping mode; default 5s in positional mode. */
    @Override
    public Duration getStaleDataThreshold() {
        return nakedOptionConfig.isScalpingMode() ? SCALPING_STALE_THRESHOLD : super.getStaleDataThreshold();
    }

    // ========================
    // ENTRY
    // ========================

    /**
     * Entry requires autoEntry enabled and within the configured entry window.
     * No IV or other complex conditions for naked plays.
     */
    @Override
    protected boolean shouldEnter(MarketSnapshot snapshot) {
        if (!nakedOptionConfig.isAutoEntry()) {
            return false;
        }
        return isWithinEntryWindow();
    }

    /**
     * Builds a single-leg order. Strike resolution:
     * 1. If explicit strike is configured, use it
     * 2. Otherwise, use ATM + strikeOffset * strikeInterval
     */
    @Override
    protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
        BigDecimal entryStrike = resolveStrike(snapshot.getSpotPrice());

        return List.of(OrderRequest.builder()
                .tradingSymbol(buildTradingSymbol(entryStrike, optionType))
                .side(side)
                .type(OrderType.MARKET)
                .quantity(1)
                .build());
    }

    // ========================
    // EXIT
    // ========================

    /**
     * Dual-mode exit logic:
     * - Scalping: point-based (targetPoints, stopLossPoints, maxHoldDuration)
     * - Positional: %-based (targetPercent, stopLossMultiplier, minDaysToExpiry)
     */
    @Override
    protected boolean shouldExit(MarketSnapshot snapshot) {
        if (positions.isEmpty()) {
            return false;
        }

        if (nakedOptionConfig.isScalpingMode()) {
            return shouldExitScalping();
        } else {
            return shouldExitPositional();
        }
    }

    private boolean shouldExitScalping() {
        BigDecimal pnl = calculateTotalPnl();

        // Target hit
        BigDecimal target = nakedOptionConfig.getTargetPoints();
        if (target != null && pnl.compareTo(target) >= 0) {
            logDecision(
                    "EXIT_REASON",
                    "Target points reached",
                    Map.of("pnl", pnl.toString(), "targetPoints", target.toString()));
            return true;
        }

        // Stop loss hit
        BigDecimal stopLoss = nakedOptionConfig.getStopLossPoints();
        if (stopLoss != null && pnl.compareTo(stopLoss.negate()) <= 0) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss points hit",
                    Map.of("pnl", pnl.toString(), "stopLossPoints", stopLoss.toString()));
            return true;
        }

        // Max hold duration exceeded
        Duration maxHold = nakedOptionConfig.getMaxHoldDuration();
        if (maxHold != null && entryTime != null) {
            Duration held = Duration.between(entryTime, LocalDateTime.now());
            if (held.compareTo(maxHold) > 0) {
                logDecision(
                        "EXIT_REASON",
                        "Max hold duration exceeded",
                        Map.of(
                                "heldSeconds", String.valueOf(held.toSeconds()),
                                "maxSeconds", String.valueOf(maxHold.toSeconds())));
                return true;
            }
        }

        return false;
    }

    private boolean shouldExitPositional() {
        // Target: P&L >= entryPremium * targetPercent
        if (isTargetReached(nakedOptionConfig.getTargetPercent())) {
            logDecision(
                    "EXIT_REASON",
                    "Target profit reached",
                    Map.of("targetPercent", nakedOptionConfig.getTargetPercent().toString()));
            return true;
        }

        // Stop loss: P&L <= -(entryPremium * stopLossMultiplier)
        if (isStopLossHit(nakedOptionConfig.getStopLossMultiplier())) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss hit",
                    Map.of(
                            "stopLossMultiplier",
                            nakedOptionConfig.getStopLossMultiplier().toString()));
            return true;
        }

        // DTE exit
        if (isDteExitTriggered(nakedOptionConfig.getMinDaysToExpiry())) {
            logDecision(
                    "EXIT_REASON",
                    "DTE exit triggered",
                    Map.of("minDaysToExpiry", nakedOptionConfig.getMinDaysToExpiry()));
            return true;
        }

        return false;
    }

    // ========================
    // ADJUSTMENT
    // ========================

    /** Naked options don't adjust -- they exit on target/SL. */
    @Override
    protected void adjust(MarketSnapshot snapshot) {
        // No-op: simple directional plays exit, they don't adjust
    }

    // ========================
    // MORPHING
    // ========================

    /** Naked options don't morph. */
    @Override
    public List<StrategyType> supportedMorphs() {
        return List.of();
    }

    // ========================
    // INTERNALS
    // ========================

    /**
     * Resolves the entry strike price:
     * 1. If explicit strike is set, use it directly
     * 2. Otherwise, compute ATM + offset (offset direction depends on CE/PE)
     */
    private BigDecimal resolveStrike(BigDecimal spotPrice) {
        if (nakedOptionConfig.getStrike() != null) {
            return nakedOptionConfig.getStrike();
        }

        BigDecimal atm = roundToStrike(spotPrice);
        int offset = nakedOptionConfig.getStrikeOffset();

        if (offset == 0) {
            return atm;
        }

        BigDecimal interval = nakedOptionConfig.getStrikeInterval();
        // For CE: positive offset = higher strike (OTM), negative = lower (ITM)
        // For PE: positive offset = lower strike (OTM), negative = higher (ITM)
        BigDecimal shift = interval.multiply(BigDecimal.valueOf(offset));
        if ("PE".equals(optionType)) {
            shift = shift.negate();
        }

        return atm.add(shift);
    }

    /**
     * Builds a placeholder trading symbol for the order.
     * #TODO Task 8.3: Replace with InstrumentService.resolveSymbol() for full Kite symbol
     */
    private String buildTradingSymbol(BigDecimal strike, String optionType) {
        return nakedOptionConfig.getUnderlying() + strike.stripTrailingZeros().toPlainString() + optionType;
    }

    /** Derives option type from strategy type. */
    private static String deriveOptionType(StrategyType type) {
        return switch (type) {
            case CE_BUY, CE_SELL -> "CE";
            case PE_BUY, PE_SELL -> "PE";
            default -> throw new IllegalArgumentException("Not a naked option type: " + type);
        };
    }

    /** Derives order side from strategy type. */
    private static OrderSide deriveSide(StrategyType type) {
        return switch (type) {
            case CE_BUY, PE_BUY -> OrderSide.BUY;
            case CE_SELL, PE_SELL -> OrderSide.SELL;
            default -> throw new IllegalArgumentException("Not a naked option type: " + type);
        };
    }
}
