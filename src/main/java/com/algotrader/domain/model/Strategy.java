package com.algotrader.domain.model;

import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.enums.TradingMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * A trading strategy configuration with its legs and adjustment rules.
 *
 * <p>Strategies are the central orchestration unit: they define which options to
 * trade (legs), when to enter/exit (conditions stored in config JSON), and how
 * to adjust (adjustment rules). Each strategy type (straddle, iron condor, etc.)
 * has a dedicated implementation extending BaseStrategy.
 *
 * <p>Lifecycle: CREATED → ARMED (monitoring for entry) → ACTIVE (positions open)
 * → PAUSED/MORPHING/CLOSING → CLOSED. A strategy can be re-armed after closing,
 * creating a new StrategyRun.
 *
 * <p>The config field stores strategy-type-specific parameters as JSON
 * (e.g., entry time window, IV threshold, target profit %). It is deserialized
 * into the appropriate config class (StraddleConfig, IronCondorConfig, etc.)
 * by the StrategyFactory.
 */
@Data
@Builder
public class Strategy {

    private String id;
    private String name;
    private String description;
    private StrategyType type;
    private StrategyStatus status;
    private TradingMode tradingMode;

    /** Root underlying symbol, e.g., "NIFTY", "BANKNIFTY". */
    private String underlying;

    private LocalDate expiry;

    /** Component legs of this strategy (e.g., 2 legs for straddle, 4 for iron condor). */
    private List<StrategyLeg> legs;

    /** Rules that define when and how to adjust positions automatically. */
    private List<AdjustmentRule> adjustmentRules;

    /**
     * Strategy-type-specific configuration stored as JSON string.
     * Deserialized by StrategyFactory into the appropriate typed config
     * (e.g., StraddleConfig, IronCondorConfig).
     */
    private String config;

    private LocalDateTime createdAt;
    private LocalDateTime deployedAt;
    private LocalDateTime closedAt;

    /** Cumulative P&L across all runs of this strategy. */
    private BigDecimal totalPnl;
}
