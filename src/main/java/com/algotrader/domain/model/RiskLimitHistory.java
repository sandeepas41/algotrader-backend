package com.algotrader.domain.model;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * Audit trail for risk limit changes.
 *
 * <p>Every time a risk limit is modified (e.g., daily loss limit changed from
 * 50000 to 75000), a history entry is created. This provides accountability
 * and enables analysis of how risk parameters evolved over time.
 */
@Data
@Builder
public class RiskLimitHistory {

    private Long id;

    /** Category of limit (e.g., "DAILY_LOSS", "MAX_POSITIONS", "MARGIN_UTILIZATION"). */
    private String limitType;

    /** Human-readable name (e.g., "Daily Loss Limit", "Max Open Positions"). */
    private String limitName;

    private String oldValue;
    private String newValue;

    /** Who made the change (user ID or "SYSTEM" for automated adjustments). */
    private String changedBy;

    private String reason;
    private LocalDateTime timestamp;
}
