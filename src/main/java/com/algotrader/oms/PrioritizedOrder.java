package com.algotrader.oms;

import com.algotrader.domain.enums.OrderPriority;
import lombok.Builder;
import lombok.Data;

/**
 * Wraps an {@link OrderRequest} with priority metadata for the {@link OrderQueue}.
 *
 * <p>Orders are sorted first by priority level (lower = higher priority), then by
 * sequence number (FIFO within same priority). The sequence number is a monotonically
 * increasing counter assigned at enqueue time, ensuring deterministic ordering even
 * when multiple orders arrive at the same priority level simultaneously.
 *
 * <p>The {@code enqueuedAt} timestamp is used for queue latency monitoring (how long
 * an order waited in the queue before processing).
 */
@Data
@Builder
public class PrioritizedOrder {

    private OrderRequest orderRequest;
    private OrderPriority priority;
    private long sequenceNumber;
    private long enqueuedAt;
}
