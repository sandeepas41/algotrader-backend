package com.algotrader.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * JPA entity for the risk_limit_history table.
 * Audit trail for every risk limit modification (e.g., daily loss limit changed from 50K to 75K).
 * Provides accountability and enables analysis of how risk parameters evolved over time.
 */
@Entity
@Table(name = "risk_limit_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskLimitHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Category: DAILY_LOSS, MAX_POSITIONS, MARGIN_UTILIZATION, etc. */
    @Column(name = "limit_type", length = 50)
    private String limitType;

    /** Human-readable name: "Daily Loss Limit", "Max Open Positions". */
    @Column(name = "limit_name", length = 100)
    private String limitName;

    @Column(name = "old_value", length = 100)
    private String oldValue;

    @Column(name = "new_value", length = 100)
    private String newValue;

    /** Who made the change (user ID or "SYSTEM" for automated adjustments). */
    @Column(name = "changed_by", length = 100)
    private String changedBy;

    @Column(columnDefinition = "TEXT")
    private String reason;

    private LocalDateTime timestamp;
}
