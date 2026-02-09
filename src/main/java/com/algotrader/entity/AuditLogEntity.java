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
 * JPA entity for the audit_logs table.
 * Immutable audit trail for all state changes in the system (order placed, strategy updated, etc.).
 * Supports a context_json column that captures the market snapshot at decision time,
 * enabling post-hoc analysis of why a particular action was taken.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", length = 50)
    private String eventType;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Column(name = "entity_id", length = 36)
    private String entityId;

    @Column(length = 50)
    private String action;

    @Column(name = "old_value", columnDefinition = "JSON")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "JSON")
    private String newValue;

    /** Market snapshot at decision time (spot price, IV, delta, margin utilization, etc.). */
    @Column(name = "context_json", columnDefinition = "JSON")
    private String contextJson;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    private LocalDateTime timestamp;
}
