package com.algotrader.domain.model;

import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.enums.OrderSide;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Defines a new leg to open during a morph operation.
 *
 * <p>The instrument is resolved at execution time from the underlying,
 * strike, option type, and expiry of the source strategy.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewLegDefinition {
    private BigDecimal strike;
    private InstrumentType optionType;
    private OrderSide side;
    /** If null, inherits from source strategy or morph request overrideLots. */
    private Integer lots;
}
