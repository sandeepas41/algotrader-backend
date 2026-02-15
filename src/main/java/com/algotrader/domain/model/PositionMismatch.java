package com.algotrader.domain.model;

import com.algotrader.domain.enums.MismatchType;
import com.algotrader.domain.enums.ResolutionStrategy;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * A single discrepancy found during position reconciliation between our local
 * state (Redis) and the broker's state (Kite API).
 *
 * <p>Mismatches indicate potential issues: manual trades not captured locally,
 * failed order updates, or stale cache data. The reconciliation service creates
 * one PositionMismatch per discrepant instrument and applies a resolution strategy.
 */
@Data
@Builder
public class PositionMismatch {

    private Long instrumentToken;
    private String tradingSymbol;
    private MismatchType type;
    private ResolutionStrategy resolution;

    // Broker state
    private Integer brokerQuantity;
    private BigDecimal brokerAveragePrice;
    private BigDecimal brokerPnl;

    // Local state
    private Integer localQuantity;
    private BigDecimal localAveragePrice;
    private BigDecimal localPnl;

    // Resolution outcome
    private boolean resolved;
    private String resolutionDetail;
}
