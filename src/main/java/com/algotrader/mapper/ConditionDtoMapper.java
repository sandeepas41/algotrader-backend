package com.algotrader.mapper;

import com.algotrader.api.dto.request.CompositeConditionRuleRequest;
import com.algotrader.api.dto.request.ConditionRuleRequest;
import com.algotrader.api.dto.response.CompositeConditionRuleResponse;
import com.algotrader.api.dto.response.ConditionRuleResponse;
import com.algotrader.api.dto.response.ConditionTriggerHistoryResponse;
import com.algotrader.domain.model.CompositeConditionRule;
import com.algotrader.domain.model.ConditionRule;
import com.algotrader.domain.model.ConditionTriggerHistory;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * MapStruct mapper for condition engine DTOs.
 *
 * <p>Handles conversion between REST request/response DTOs and domain models.
 * Used by ConditionController at the API boundary. The controller maps
 * DTO -> domain for writes and domain -> DTO for reads.
 */
@Mapper
public interface ConditionDtoMapper {

    // ConditionRule: Request -> Domain
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "triggerCount", ignore = true)
    @Mapping(target = "lastTriggeredAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    ConditionRule toDomain(ConditionRuleRequest request);

    // ConditionRule: Domain -> Response
    ConditionRuleResponse toResponse(ConditionRule domain);

    List<ConditionRuleResponse> toResponseList(List<ConditionRule> domains);

    // Update existing domain from request
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "triggerCount", ignore = true)
    @Mapping(target = "lastTriggeredAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateDomainFromRequest(ConditionRuleRequest request, @MappingTarget ConditionRule domain);

    // CompositeConditionRule: Request -> Domain
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    CompositeConditionRule toDomain(CompositeConditionRuleRequest request);

    // CompositeConditionRule: Domain -> Response
    CompositeConditionRuleResponse toResponse(CompositeConditionRule domain);

    List<CompositeConditionRuleResponse> toCompositeResponseList(List<CompositeConditionRule> domains);

    // ConditionTriggerHistory: Domain -> Response
    ConditionTriggerHistoryResponse toResponse(ConditionTriggerHistory domain);

    List<ConditionTriggerHistoryResponse> toHistoryResponseList(List<ConditionTriggerHistory> domains);
}
