package com.algotrader.api.dto.response;

import com.algotrader.domain.enums.StrategyType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO response for a single lineage record (parent-child morph link).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyLineageResponse {
    private Long id;
    private String parentStrategyId;
    private String childStrategyId;
    private StrategyType parentStrategyType;
    private StrategyType childStrategyType;
    private String morphReason;
    private BigDecimal parentPnlAtMorph;
    private LocalDateTime morphedAt;
}
