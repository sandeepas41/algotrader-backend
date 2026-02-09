package com.algotrader.domain.model;

import com.algotrader.domain.enums.OrderSide;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A step in the morph plan that closes a leg from the source strategy.
 * The close order is a market order on the opposite side of the position.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LegCloseStep {
    private String positionId;
    private Long instrumentToken;
    private String tradingSymbol;
    private int quantity;
    /** Opposite of the position's side. */
    private OrderSide closeSide;

    @Builder.Default
    private String status = "PENDING";

    private String orderId;
}
