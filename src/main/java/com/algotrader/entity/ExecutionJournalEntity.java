package com.algotrader.entity;

import com.algotrader.domain.enums.JournalStatus;
import com.algotrader.domain.enums.OrderSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for the execution_journal table.
 * Write-ahead log (WAL) for multi-leg operations (entry, adjustment, kill switch).
 * Each row tracks one leg of a multi-leg operation, grouped by execution_group_id.
 * On startup, StartupRecoveryService checks for incomplete journals and resolves them.
 */
@Entity
@Table(name = "execution_journal")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionJournalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_id", length = 36)
    private String strategyId;

    /** Groups all legs of the same multi-leg operation. */
    @Column(name = "execution_group_id", length = 36)
    private String executionGroupId;

    /** MULTI_LEG_ENTRY, ADJUSTMENT, KILL_SWITCH, EXIT. */
    @Column(name = "operation_type", length = 50)
    private String operationType;

    /** Zero-based index of this leg within the operation. */
    @Column(name = "leg_index")
    private int legIndex;

    @Column(name = "total_legs")
    private int totalLegs;

    @Column(name = "instrument_token")
    private Long instrumentToken;

    @Column(name = "trading_symbol", length = 50)
    private String tradingSymbol;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(10)")
    private OrderSide side;

    private int quantity;

    /** Kite order ID after placement. Null while PENDING. */
    @Column(name = "broker_order_id", length = 100)
    private String brokerOrderId;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(50)")
    private JournalStatus status;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
