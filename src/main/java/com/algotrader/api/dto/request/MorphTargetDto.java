package com.algotrader.api.dto.request;

import com.algotrader.domain.enums.StrategyType;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO for a single morph target within a MorphRequestDto.
 *
 * <p>Defines the target strategy type, which legs to retain, which new
 * legs to open, and any strategy-specific parameter overrides.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MorphTargetDto {

    @NotNull(message = "Target strategy type is required")
    private StrategyType strategyType;

    /** Leg identifiers to retain from source (e.g., "SELL_CE", "BUY_PE"). */
    private List<String> retainedLegs;

    /** New legs to open for this target strategy. */
    private List<NewLegDefinitionDto> newLegs;

    /** Strategy-specific configuration overrides. */
    private Map<String, Object> parameters;
}
