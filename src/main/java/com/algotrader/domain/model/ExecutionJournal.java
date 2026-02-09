package com.algotrader.domain.model;

import com.algotrader.domain.enums.JournalStatus;
import com.algotrader.domain.enums.OrderSide;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * Write-ahead log (WAL) entry for multi-leg operations (entry, adjustment, kill switch).
 *
 * <p>Each row represents one leg of a multi-leg operation. The executionGroupId groups
 * all legs of the same operation. Before executing any leg, a PENDING journal entry is
 * created. As each leg completes, its status is updated.
 *
 * <p>On startup, StartupRecoveryService checks for journals with status IN_PROGRESS
 * or REQUIRES_RECOVERY and resolves them — either completing remaining legs or
 * rolling back (closing already-filled legs). This prevents half-executed strategies
 * after a crash.
 */
@Data
@Builder
public class ExecutionJournal {

    private Long id;
    private String strategyId;

    /** Groups all legs of the same multi-leg operation. */
    private String executionGroupId;

    /** Type of operation: MULTI_LEG_ENTRY, ADJUSTMENT, KILL_SWITCH, EXIT. */
    private String operationType;

    /** Zero-based index of this leg within the operation. */
    private int legIndex;

    /** Total number of legs in this operation. */
    private int totalLegs;

    private Long instrumentToken;
    private String tradingSymbol;
    private OrderSide side;
    private int quantity;

    /** Kite order ID after placement. Null while PENDING. */
    private String brokerOrderId;

    private JournalStatus status;

    /** Reason for failure, if status is FAILED. */
    private String failureReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
