package com.algotrader.oms;

import lombok.Builder;
import lombok.Data;

/**
 * Result of routing an order through the {@link OrderRouter} pipeline.
 *
 * <p>Contains a boolean indicating acceptance or rejection, plus context-specific
 * fields. If accepted, {@code orderId} is the broker-assigned order ID (set after
 * execution) or null if the order was enqueued for deferred execution. If rejected,
 * {@code rejectionReason} explains why (kill switch active, duplicate, risk violation).
 */
@Data
@Builder
public class OrderRouteResult {

    private boolean accepted;

    /** Broker-assigned order ID. Set after successful placement. Null if only enqueued. */
    private String orderId;

    /** Human-readable reason for rejection. Null if accepted. */
    private String rejectionReason;

    public static OrderRouteResult accepted(String orderId) {
        return OrderRouteResult.builder().accepted(true).orderId(orderId).build();
    }

    public static OrderRouteResult rejected(String reason) {
        return OrderRouteResult.builder()
                .accepted(false)
                .rejectionReason(reason)
                .build();
    }
}
