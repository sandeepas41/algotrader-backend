package com.algotrader.domain.model;

import com.algotrader.domain.enums.InstrumentType;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * A single leg of a multi-leg option strategy.
 *
 * <p>Each leg defines an option type (CE/PE), strike selection logic, and quantity.
 * For example, a straddle has 2 legs (ATM CE sell + ATM PE sell), while an iron condor
 * has 4 legs (OTM CE sell + further OTM CE buy + OTM PE sell + further OTM PE buy).
 *
 * <p>Quantity sign convention: positive = buy, negative = sell. This matches
 * the position quantity convention and simplifies net position calculations.
 *
 * <p>When the strategy is ACTIVE, positionId links to the actual open position.
 * When CREATED/ARMED, positionId is null and strikeSelection defines how the
 * strike will be chosen at entry time.
 */
@Data
@Builder
public class StrategyLeg {

    private String id;
    private String strategyId;

    /** CE (call) or PE (put). Uses InstrumentType since options are instruments. */
    private InstrumentType optionType;

    /** Actual strike price. Resolved from strikeSelection at entry time. */
    private BigDecimal strike;

    /** Signed quantity: positive = buy, negative = sell. */
    private int quantity;

    /** How to select the strike at entry time. Stored as JSON in DB. */
    private StrikeSelection strikeSelection;

    /** Linked position ID when this leg is active. Null before entry. */
    private String positionId;
}
