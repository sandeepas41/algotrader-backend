package com.algotrader.api.controller;

import com.algotrader.api.dto.request.MorphRequestDto;
import com.algotrader.api.dto.response.MorphPlanResponse;
import com.algotrader.api.dto.response.MorphPreviewResponse;
import com.algotrader.api.dto.response.MorphResultResponse;
import com.algotrader.api.dto.response.StrategyLineageResponse;
import com.algotrader.api.dto.response.StrategyLineageTreeResponse;
import com.algotrader.domain.model.MorphExecutionPlan;
import com.algotrader.domain.model.MorphPlan;
import com.algotrader.domain.model.MorphRequest;
import com.algotrader.domain.model.MorphResult;
import com.algotrader.domain.model.StrategyLineage;
import com.algotrader.domain.model.StrategyLineageTree;
import com.algotrader.mapper.MorphDtoMapper;
import com.algotrader.morph.MorphService;
import com.algotrader.morph.StrategyLineageService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import org.mapstruct.factory.Mappers;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for strategy morphing operations and lineage queries.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/morph} -- execute a morph</li>
 *   <li>{@code POST /api/morph/preview} -- preview a morph (dry run)</li>
 *   <li>{@code GET /api/morph/plans} -- list all morph plans</li>
 *   <li>{@code GET /api/morph/plans/{id}} -- get a specific plan</li>
 *   <li>{@code GET /api/morph/lineage/{strategyId}} -- get lineage tree</li>
 *   <li>{@code GET /api/morph/lineage/{strategyId}/cumulative-pnl} -- cumulative P&L</li>
 *   <li>{@code GET /api/morph/lineage} -- all lineage records</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/morph")
public class MorphController {

    private final MorphService morphService;
    private final StrategyLineageService strategyLineageService;

    private final MorphDtoMapper morphDtoMapper = Mappers.getMapper(MorphDtoMapper.class);

    public MorphController(MorphService morphService, StrategyLineageService strategyLineageService) {
        this.morphService = morphService;
        this.strategyLineageService = strategyLineageService;
    }

    // ========================
    // MORPH OPERATIONS
    // ========================

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public MorphResultResponse morph(@RequestBody @Valid MorphRequestDto requestDto) {
        MorphRequest request = morphDtoMapper.toDomain(requestDto);
        MorphResult result = morphService.morph(request);
        return morphDtoMapper.toResponse(result);
    }

    @PostMapping("/preview")
    public MorphPreviewResponse preview(@RequestBody @Valid MorphRequestDto requestDto) {
        MorphRequest request = morphDtoMapper.toDomain(requestDto);
        MorphExecutionPlan plan = morphService.preview(request);
        return morphDtoMapper.toPreviewResponse(plan);
    }

    // ========================
    // MORPH PLANS
    // ========================

    @GetMapping("/plans")
    public List<MorphPlanResponse> getAllPlans() {
        List<MorphPlan> plans = morphService.getAllPlans();
        return morphDtoMapper.toPlanResponseList(plans);
    }

    @GetMapping("/plans/{id}")
    public MorphPlanResponse getPlan(@PathVariable Long id) {
        MorphPlan plan = morphService.getPlan(id);
        return morphDtoMapper.toResponse(plan);
    }

    // ========================
    // LINEAGE
    // ========================

    @GetMapping("/lineage")
    public List<StrategyLineageResponse> getAllLineage() {
        List<StrategyLineage> lineage = strategyLineageService.getAllLineage();
        return morphDtoMapper.toLineageResponseList(lineage);
    }

    @GetMapping("/lineage/{strategyId}")
    public StrategyLineageTreeResponse getLineageTree(@PathVariable String strategyId) {
        StrategyLineageTree tree = strategyLineageService.getLineageTree(strategyId);
        return morphDtoMapper.toResponse(tree);
    }

    @GetMapping("/lineage/{strategyId}/cumulative-pnl")
    public BigDecimal getCumulativePnl(@PathVariable String strategyId) {
        return strategyLineageService.getCumulativePnl(strategyId);
    }
}
