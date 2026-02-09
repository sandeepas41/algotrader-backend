package com.algotrader.oms;

import lombok.Builder;
import lombok.Data;

/**
 * Result of an order amendment (modification) attempt.
 *
 * <p>On success, {@code accepted} is true and the order parameters have been
 * updated at the broker. On failure, the rejection reason indicates what went
 * wrong (invalid state, validation failure, or broker rejection).
 */
@Data
@Builder
public class OrderAmendmentResult {

    private boolean accepted;
    private String orderId;
    private String rejectionReason;

    public static OrderAmendmentResult success(String orderId) {
        return OrderAmendmentResult.builder().accepted(true).orderId(orderId).build();
    }

    public static OrderAmendmentResult rejected(String reason) {
        return OrderAmendmentResult.builder()
                .accepted(false)
                .rejectionReason(reason)
                .build();
    }
}
