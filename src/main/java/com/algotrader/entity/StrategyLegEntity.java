package com.algotrader.entity;

import com.algotrader.domain.enums.InstrumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the strategy_legs table.
 * Each row represents one leg of a multi-leg option strategy (e.g., 2 for straddle, 4 for iron condor).
 * Strike selection logic is stored as JSON and resolved at entry time.
 */
@Entity
@Table(name = "strategy_legs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyLegEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "strategy_id", length = 36)
    private String strategyId;

    /** CE (call) or PE (put). */
    @Enumerated(EnumType.STRING)
    @Column(name = "option_type", columnDefinition = "varchar(10)")
    private InstrumentType optionType;

    @Column(precision = 10, scale = 2)
    private BigDecimal strike;

    /** Signed quantity: positive = buy, negative = sell. */
    private int quantity;

    /** JSON-serialized StrikeSelection (type + offset + fixedStrike). */
    @Column(name = "strike_selection", columnDefinition = "JSON")
    private String strikeSelection;

    /** Linked position when this leg is active. Null before entry. */
    @Column(name = "position_id", length = 36)
    private String positionId;
}
