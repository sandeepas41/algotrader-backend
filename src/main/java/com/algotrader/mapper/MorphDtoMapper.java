package com.algotrader.mapper;

import com.algotrader.api.dto.request.MorphRequestDto;
import com.algotrader.api.dto.request.MorphTargetDto;
import com.algotrader.api.dto.request.NewLegDefinitionDto;
import com.algotrader.api.dto.response.MorphPlanResponse;
import com.algotrader.api.dto.response.MorphPreviewResponse;
import com.algotrader.api.dto.response.MorphResultResponse;
import com.algotrader.api.dto.response.SimpleMorphPreviewResponse;
import com.algotrader.api.dto.response.StrategyLineageResponse;
import com.algotrader.api.dto.response.StrategyLineageTreeResponse;
import com.algotrader.domain.model.MorphExecutionPlan;
import com.algotrader.domain.model.MorphPlan;
import com.algotrader.domain.model.MorphRequest;
import com.algotrader.domain.model.MorphResult;
import com.algotrader.domain.model.MorphTarget;
import com.algotrader.domain.model.NewLegDefinition;
import com.algotrader.domain.model.SimpleMorphPlan;
import com.algotrader.domain.model.StrategyLineage;
import com.algotrader.domain.model.StrategyLineageTree;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for morph DTOs <-> domain model conversions.
 *
 * <p>Handles conversions for the MorphController layer:
 * request DTOs to domain models, and domain results to response DTOs.
 */
@Mapper
public interface MorphDtoMapper {

    // Request DTO -> Domain
    MorphRequest toDomain(MorphRequestDto dto);

    MorphTarget toDomain(MorphTargetDto dto);

    NewLegDefinition toDomain(NewLegDefinitionDto dto);

    // Domain -> Response DTO
    MorphResultResponse toResponse(MorphResult morphResult);

    MorphPlanResponse toResponse(MorphPlan morphPlan);

    List<MorphPlanResponse> toPlanResponseList(List<MorphPlan> plans);

    StrategyLineageResponse toResponse(StrategyLineage lineage);

    List<StrategyLineageResponse> toLineageResponseList(List<StrategyLineage> lineages);

    @Mapping(target = "ancestors", source = "ancestors")
    @Mapping(target = "descendants", source = "descendants")
    StrategyLineageTreeResponse toResponse(StrategyLineageTree tree);

    // SimpleMorphPlan -> SimpleMorphPreviewResponse (simplified morph API)
    SimpleMorphPreviewResponse toSimplePreviewResponse(SimpleMorphPlan plan);

    // Preview: MorphExecutionPlan -> MorphPreviewResponse
    default MorphPreviewResponse toPreviewResponse(MorphExecutionPlan plan) {
        return MorphPreviewResponse.builder()
                .sourceStrategyId(plan.getSourceStrategyId())
                .sourceType(plan.getSourceType())
                .legsToCloseCount(
                        plan.getLegsToClose() != null ? plan.getLegsToClose().size() : 0)
                .legsToReassignCount(
                        plan.getLegsToReassign() != null
                                ? plan.getLegsToReassign().size()
                                : 0)
                .legsToOpenCount(
                        plan.getLegsToOpen() != null ? plan.getLegsToOpen().size() : 0)
                .strategiesToCreateCount(
                        plan.getStrategiesToCreate() != null
                                ? plan.getStrategiesToCreate().size()
                                : 0)
                .targetTypes(
                        plan.getStrategiesToCreate() != null
                                ? plan.getStrategiesToCreate().stream()
                                        .map(s -> s.getStrategyType())
                                        .toList()
                                : List.of())
                .build();
    }
}
