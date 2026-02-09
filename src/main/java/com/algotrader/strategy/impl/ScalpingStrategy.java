package com.algotrader.strategy.impl;

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
 * Ultra-fast scalping strategy: single-leg entry/exit with tick-level monitoring.
 *
 * <p><b>Market view:</b> Short-term directional momentum. Profits from quick price
 * movements in the selected option. No adjustments -- scalps exit fast on target or stop loss.
 *
 * <p><b>Legs (1):</b>
 * <ol>
 *   <li>Single leg: BUY or SELL at the configured strike and option type (CE/PE)</li>
 * </ol>
 *
 * <p><b>Entry conditions:</b>
 * <ul>
 *   <li>autoEntry must be enabled (otherwise entries are manual via API)</li>
 *   <li>Within configured entry time window</li>
 * </ul>
 *
 * <p><b>Exit conditions (point-based, not percentage-based):</b>
 * <ul>
 *   <li>Target: P&L >= targetPoints</li>
 *   <li>Stop loss: P&L <= -(stopLossPoints)</li>
 *   <li>Max hold duration exceeded (time-based forced exit)</li>
 * </ul>
 *
 * <p><b>Adjustments:</b> None. Scalping strategies exit fast, they don't adjust.
 *
 * <p><b>Stale data threshold:</b> 2 seconds (tighter than the 5s positional default).
 * Scalping requires real-time data accuracy for split-second decisions.
 *
 * <p><b>Monitoring interval:</b> Duration.ZERO (every tick).
 */
public class ScalpingStrategy extends BaseStrategy {

    private static final Logger log = LoggerFactory.getLogger(ScalpingStrategy.class);

    /** Scalping uses a tighter 2-second stale data threshold. */
    private static final Duration STALE_DATA_THRESHOLD = Duration.ofSeconds(2);

    private final ScalpingConfig scalpingConfig;

    public ScalpingStrategy(String id, String name, ScalpingConfig scalpingConfig) {
        super(id, name, scalpingConfig);
        this.scalpingConfig = scalpingConfig;
    }

    // ========================
    // IDENTITY
    // ========================

    @Override
    public StrategyType getType() {
        return StrategyType.SCALPING;
    }

    /** Tick-level monitoring: evaluate on every tick. */
    @Override
    public Duration getMonitoringInterval() {
        return Duration.ZERO;
    }

    /** Tighter stale data threshold for scalping: 2 seconds instead of 5. */
    @Override
    public Duration getStaleDataThreshold() {
        return STALE_DATA_THRESHOLD;
    }

    // ========================
    // ENTRY
    // ========================

    /**
     * Entry requires:
     * 1. autoEntry enabled (manual entries bypass shouldEnter via API)
     * 2. Current time within the configured entry window
     */
    @Override
    protected boolean shouldEnter(MarketSnapshot snapshot) {
        if (!scalpingConfig.isAutoEntry()) {
            return false;
        }

        return isWithinEntryWindow();
    }

    /**
     * Builds a single-leg order at the configured strike and option type.
     * If no explicit strike is configured, uses ATM.
     */
    @Override
    protected List<OrderRequest> buildEntryOrders(MarketSnapshot snapshot) {
        BigDecimal entryStrike = scalpingConfig.getStrike();
        if (entryStrike == null) {
            entryStrike = roundToStrike(snapshot.getSpotPrice());
        }

        String optionType = scalpingConfig.getOptionType();
        if (optionType == null) {
            optionType = "CE";
        }

        return List.of(OrderRequest.builder()
                .tradingSymbol(buildTradingSymbol(entryStrike, optionType))
                .side(scalpingConfig.getSide())
                .type(OrderType.MARKET)
                .quantity(1)
                .build());
    }

    // ========================
    // EXIT
    // ========================

    /**
     * Point-based exit conditions:
     * 1. Target: P&L >= targetPoints
     * 2. Stop loss: P&L <= -(stopLossPoints)
     * 3. Max hold duration exceeded
     */
    @Override
    protected boolean shouldExit(MarketSnapshot snapshot) {
        if (positions.isEmpty()) {
            return false;
        }

        BigDecimal pnl = calculateTotalPnl();

        // Target hit
        BigDecimal target = scalpingConfig.getTargetPoints();
        if (target != null && pnl.compareTo(target) >= 0) {
            logDecision(
                    "EXIT_REASON",
                    "Target points reached",
                    Map.of(
                            "pnl", pnl.toString(),
                            "targetPoints", target.toString()));
            return true;
        }

        // Stop loss hit
        BigDecimal stopLoss = scalpingConfig.getStopLossPoints();
        if (stopLoss != null && pnl.compareTo(stopLoss.negate()) <= 0) {
            logDecision(
                    "EXIT_REASON",
                    "Stop loss points hit",
                    Map.of(
                            "pnl", pnl.toString(),
                            "stopLossPoints", stopLoss.toString()));
            return true;
        }

        // Max hold duration exceeded
        Duration maxHold = scalpingConfig.getMaxHoldDuration();
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

    // ========================
    // ADJUSTMENT
    // ========================

    /** Scalping strategies don't adjust -- they exit fast. */
    @Override
    protected void adjust(MarketSnapshot snapshot) {
        // No-op: scalps exit, they don't adjust
    }

    // ========================
    // MORPHING
    // ========================

    /** Scalping strategies don't morph. */
    @Override
    public List<StrategyType> supportedMorphs() {
        return List.of();
    }

    // ========================
    // INTERNALS
    // ========================

    /**
     * Builds a placeholder trading symbol for the order.
     * #TODO Task 8.3: Replace with InstrumentService.resolveSymbol() for full Kite symbol
     */
    private String buildTradingSymbol(BigDecimal strike, String optionType) {
        return scalpingConfig.getUnderlying() + strike.stripTrailingZeros().toPlainString() + optionType;
    }
}
