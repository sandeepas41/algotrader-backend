package com.algotrader.api.dto.response;

import com.algotrader.domain.enums.MorphPlanStatus;
import com.algotrader.domain.enums.StrategyType;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO response for a morph plan (WAL entry).
 *
 * <p>Returned by the plans endpoint. Includes plan metadata
 * and lifecycle timestamps but omits the full planDetails JSON
 * to keep the list response lightweight.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MorphPlanResponse {
    private Long id;
    private String sourceStrategyId;
    private StrategyType sourceStrategyType;
    private String targetTypes;
    private MorphPlanStatus status;
    private String reason;
    private LocalDateTime createdAt;
    private LocalDateTime executedAt;
    private LocalDateTime completedAt;
    private String errorMessage;
}
