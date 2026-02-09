package com.algotrader.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO for morph request from the frontend.
 *
 * <p>Specifies the source strategy and target strategy configurations.
 * Validated at the controller layer before conversion to the MorphRequest domain model.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MorphRequestDto {

    @NotBlank(message = "Source strategy ID is required")
    private String sourceStrategyId;

    @NotEmpty(message = "At least one morph target is required")
    @Valid
    private List<MorphTargetDto> targets;

    private Boolean copyEntryPrices;
    private Boolean autoArm;
    private Integer overrideLots;
    private String reason;
}
