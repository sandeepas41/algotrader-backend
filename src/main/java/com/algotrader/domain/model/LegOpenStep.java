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
 * A step in the morph plan that opens a new leg for a target strategy.
 * A market order is placed for the specified instrument.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LegOpenStep {
    private Long instrumentToken;
    private String tradingSymbol;
    private BigDecimal strike;
    private InstrumentType optionType;
    private OrderSide side;
    private int quantity;
    private String targetStrategyId;

    @Builder.Default
    private String status = "PENDING";

    private String orderId;
}
