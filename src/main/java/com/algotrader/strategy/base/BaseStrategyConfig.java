package com.algotrader.strategy.base;

import com.algotrader.domain.model.NewLegDefinition;
import com.algotrader.strategy.impl.BearCallSpreadConfig;
import com.algotrader.strategy.impl.BearPutSpreadConfig;
import com.algotrader.strategy.impl.BullCallSpreadConfig;
import com.algotrader.strategy.impl.BullPutSpreadConfig;
import com.algotrader.strategy.impl.CalendarSpreadConfig;
import com.algotrader.strategy.impl.DiagonalSpreadConfig;
import com.algotrader.strategy.impl.IronButterflyConfig;
import com.algotrader.strategy.impl.IronCondorConfig;
import com.algotrader.strategy.impl.LongStraddleConfig;
import com.algotrader.strategy.impl.NakedOptionConfig;
import com.algotrader.strategy.impl.StraddleConfig;
import com.algotrader.strategy.impl.StrangleConfig;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Common configuration shared by all strategy types.
 *
 * <p>Contains the minimum fields every strategy needs: underlying instrument, expiry,
 * lot count, and entry time window. Positional strategies extend this with
 * {@link PositionalStrategyConfig} (target %, stop-loss multiplier, min DTE).
 * Dual-mode strategies (NakedOptionConfig, LongStraddleConfig) extend this directly
 * with both positional and scalping exit fields, toggled by a scalpingMode flag.
 *
 * <p>Uses {@code @SuperBuilder} so subclasses can chain builder calls:
 * {@code PositionalStrategyConfig.builder().underlying("NIFTY").targetPercent(0.5).build()}.
 *
 * <p>Jackson {@code @JsonTypeInfo} enables polymorphic serialization for H2 persistence.
 * The {@code @type} property is embedded in JSON so the correct config subclass is
 * reconstructed when restoring strategies from the database on startup.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = StraddleConfig.class, name = "STRADDLE"),
    @JsonSubTypes.Type(value = StrangleConfig.class, name = "STRANGLE"),
    @JsonSubTypes.Type(value = IronCondorConfig.class, name = "IRON_CONDOR"),
    @JsonSubTypes.Type(value = IronButterflyConfig.class, name = "IRON_BUTTERFLY"),
    @JsonSubTypes.Type(value = BullCallSpreadConfig.class, name = "BULL_CALL_SPREAD"),
    @JsonSubTypes.Type(value = BearCallSpreadConfig.class, name = "BEAR_CALL_SPREAD"),
    @JsonSubTypes.Type(value = BullPutSpreadConfig.class, name = "BULL_PUT_SPREAD"),
    @JsonSubTypes.Type(value = BearPutSpreadConfig.class, name = "BEAR_PUT_SPREAD"),
    @JsonSubTypes.Type(value = CalendarSpreadConfig.class, name = "CALENDAR_SPREAD"),
    @JsonSubTypes.Type(value = DiagonalSpreadConfig.class, name = "DIAGONAL_SPREAD"),
    @JsonSubTypes.Type(value = NakedOptionConfig.class, name = "NAKED_OPTION"),
    @JsonSubTypes.Type(value = LongStraddleConfig.class, name = "LONG_STRADDLE"),
    @JsonSubTypes.Type(value = PositionalStrategyConfig.class, name = "POSITIONAL"),
})
public class BaseStrategyConfig {

    /** Root underlying symbol, e.g., "NIFTY", "BANKNIFTY". */
    private String underlying;

    /** Target expiry date for positions. */
    private LocalDate expiry;

    /** Number of lots to trade. */
    private int lots;

    /** Earliest time to enter positions (IST). */
    private LocalTime entryStartTime;

    /** Latest time to enter positions (IST). */
    private LocalTime entryEndTime;

    /**
     * Strike interval for the underlying.
     * NIFTY: 50, BANKNIFTY: 100. Used for rounding to nearest strike.
     */
    private BigDecimal strikeInterval;

    /**
     * Auto-pause P&L threshold: if strategy P&L drops below this (negative), auto-pause.
     * Null = disabled. Example: -15000 means auto-pause if losing more than 15k.
     */
    private BigDecimal autoPausePnlThreshold;

    /**
     * Auto-pause delta threshold: if absolute position delta exceeds this, auto-pause.
     * Null = disabled. Example: 0.5 means auto-pause if net delta > 0.5 or < -0.5.
     */
    private BigDecimal autoPauseDeltaThreshold;

    /**
     * FE-sent leg definitions for immediate entry (FIXED strikes + LIVE mode).
     * Null/empty for autonomous strategies that use the tick-driven shouldEnter/buildEntryOrders loop.
     */
    private List<NewLegDefinition> legConfigs;

    /**
     * If true, execute entry orders immediately on deploy (skip shouldEnter/tick loop).
     * Set automatically when FE sends all FIXED legs with LIVE trading mode.
     */
    private boolean immediateEntry;
}
