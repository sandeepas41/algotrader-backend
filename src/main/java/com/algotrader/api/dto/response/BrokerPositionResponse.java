package com.algotrader.api.dto.response;

import com.algotrader.domain.model.Greeks;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for broker positions enriched with allocation data.
 * Wraps the raw Position fields plus the computed allocatedQuantity from strategy legs.
 *
 * <p>allocatedQuantity = sum of |leg.quantity| across all StrategyLeg records linked to this position.
 * Zero means the position is fully unmanaged; equal to |quantity| means fully managed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrokerPositionResponse {

    private String id;
    private Long instrumentToken;
    private String tradingSymbol;
    private String exchange;
    private int quantity;
    private BigDecimal averagePrice;
    private BigDecimal lastPrice;
    private BigDecimal unrealizedPnl;
    private BigDecimal realizedPnl;
    private BigDecimal m2m;
    private int overnightQuantity;
    private Greeks greeks;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private LocalDateTime lastUpdated;

    /** Total quantity allocated across all strategy legs. Signed (matches position sign). */
    private int allocatedQuantity;
}
