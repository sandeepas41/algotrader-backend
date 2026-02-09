package com.algotrader.api.controller;

import com.algotrader.api.dto.request.CompositeConditionRuleRequest;
import com.algotrader.api.dto.request.ConditionRuleRequest;
import com.algotrader.api.dto.response.CompositeConditionRuleResponse;
import com.algotrader.api.dto.response.ConditionRuleResponse;
import com.algotrader.api.dto.response.ConditionTriggerHistoryResponse;
import com.algotrader.condition.ConditionEngine;
import com.algotrader.domain.enums.ConditionRuleStatus;
import com.algotrader.domain.model.CompositeConditionRule;
import com.algotrader.domain.model.ConditionRule;
import com.algotrader.domain.model.ConditionTriggerHistory;
import com.algotrader.entity.CompositeConditionRuleEntity;
import com.algotrader.entity.ConditionRuleEntity;
import com.algotrader.exception.ResourceNotFoundException;
import com.algotrader.mapper.ConditionDtoMapper;
import com.algotrader.mapper.ConditionRuleMapper;
import com.algotrader.repository.jpa.CompositeConditionRuleJpaRepository;
import com.algotrader.repository.jpa.ConditionRuleJpaRepository;
import com.algotrader.repository.jpa.ConditionTriggerHistoryJpaRepository;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import org.mapstruct.factory.Mappers;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for managing condition rules and viewing trigger history.
 *
 * <p>Provides CRUD operations for individual and composite condition rules,
 * pause/activate lifecycle controls, and trigger history queries.
 *
 * <p>All write operations refresh the ConditionEngine's in-memory cache
 * via {@code conditionEngine.loadRules()} to ensure real-time evaluation
 * reflects the latest rule configuration.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/conditions} -- create a new rule</li>
 *   <li>{@code GET /api/conditions} -- list all rules</li>
 *   <li>{@code GET /api/conditions/active} -- list active rules only</li>
 *   <li>{@code GET /api/conditions/{id}} -- get a specific rule</li>
 *   <li>{@code PUT /api/conditions/{id}} -- update a rule</li>
 *   <li>{@code POST /api/conditions/{id}/pause} -- pause a rule</li>
 *   <li>{@code POST /api/conditions/{id}/activate} -- activate a rule</li>
 *   <li>{@code DELETE /api/conditions/{id}} -- delete a rule</li>
 *   <li>{@code GET /api/conditions/{id}/history} -- trigger history for a rule</li>
 *   <li>{@code GET /api/conditions/history/recent} -- recent triggers across all rules</li>
 *   <li>{@code POST /api/conditions/composites} -- create a composite rule</li>
 *   <li>{@code GET /api/conditions/composites} -- list all composite rules</li>
 *   <li>{@code DELETE /api/conditions/composites/{id}} -- delete a composite rule</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/conditions")
public class ConditionController {

    private final ConditionRuleJpaRepository conditionRuleJpaRepository;
    private final CompositeConditionRuleJpaRepository compositeConditionRuleJpaRepository;
    private final ConditionTriggerHistoryJpaRepository conditionTriggerHistoryJpaRepository;
    private final ConditionEngine conditionEngine;

    private final ConditionRuleMapper conditionRuleMapper = Mappers.getMapper(ConditionRuleMapper.class);
    private final ConditionDtoMapper conditionDtoMapper = Mappers.getMapper(ConditionDtoMapper.class);

    public ConditionController(
            ConditionRuleJpaRepository conditionRuleJpaRepository,
            CompositeConditionRuleJpaRepository compositeConditionRuleJpaRepository,
            ConditionTriggerHistoryJpaRepository conditionTriggerHistoryJpaRepository,
            ConditionEngine conditionEngine) {
        this.conditionRuleJpaRepository = conditionRuleJpaRepository;
        this.compositeConditionRuleJpaRepository = compositeConditionRuleJpaRepository;
        this.conditionTriggerHistoryJpaRepository = conditionTriggerHistoryJpaRepository;
        this.conditionEngine = conditionEngine;
    }

    // ========================
    // INDIVIDUAL RULES
    // ========================

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConditionRuleResponse createRule(@RequestBody @Valid ConditionRuleRequest request) {
        ConditionRule rule = conditionDtoMapper.toDomain(request);
        rule.setStatus(ConditionRuleStatus.ACTIVE);
        rule.setTriggerCount(0);
        rule.setCreatedAt(LocalDateTime.now());

        if (rule.getCooldownMinutes() == null) {
            rule.setCooldownMinutes(30);
        }

        ConditionRuleEntity entity = conditionRuleMapper.toEntity(rule);
        ConditionRuleEntity saved = conditionRuleJpaRepository.save(entity);
        conditionEngine.loadRules();

        return conditionDtoMapper.toResponse(conditionRuleMapper.toDomain(saved));
    }

    @GetMapping
    public List<ConditionRuleResponse> getAllRules() {
        List<ConditionRule> rules = conditionRuleMapper.toDomainList(conditionRuleJpaRepository.findAll());
        return conditionDtoMapper.toResponseList(rules);
    }

    @GetMapping("/active")
    public List<ConditionRuleResponse> getActiveRules() {
        List<ConditionRule> rules =
                conditionRuleMapper.toDomainList(conditionRuleJpaRepository.findByStatus(ConditionRuleStatus.ACTIVE));
        return conditionDtoMapper.toResponseList(rules);
    }

    @GetMapping("/{id}")
    public ConditionRuleResponse getRule(@PathVariable Long id) {
        ConditionRuleEntity entity = conditionRuleJpaRepository
                .findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ConditionRule", String.valueOf(id)));
        return conditionDtoMapper.toResponse(conditionRuleMapper.toDomain(entity));
    }

    @PutMapping("/{id}")
    public ConditionRuleResponse updateRule(@PathVariable Long id, @RequestBody @Valid ConditionRuleRequest request) {
        ConditionRuleEntity entity = conditionRuleJpaRepository
                .findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ConditionRule", String.valueOf(id)));

        ConditionRule rule = conditionRuleMapper.toDomain(entity);
        conditionDtoMapper.updateDomainFromRequest(request, rule);
        rule.setUpdatedAt(LocalDateTime.now());

        ConditionRuleEntity updatedEntity = conditionRuleMapper.toEntity(rule);
        updatedEntity.setId(id);
        ConditionRuleEntity saved = conditionRuleJpaRepository.save(updatedEntity);
        conditionEngine.loadRules();

        return conditionDtoMapper.toResponse(conditionRuleMapper.toDomain(saved));
    }

    @PostMapping("/{id}/pause")
    public ConditionRuleResponse pauseRule(@PathVariable Long id) {
        ConditionRuleEntity entity = conditionRuleJpaRepository
                .findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ConditionRule", String.valueOf(id)));

        entity.setStatus(ConditionRuleStatus.PAUSED);
        entity.setUpdatedAt(LocalDateTime.now());

        ConditionRuleEntity saved = conditionRuleJpaRepository.save(entity);
        conditionEngine.loadRules();

        return conditionDtoMapper.toResponse(conditionRuleMapper.toDomain(saved));
    }

    @PostMapping("/{id}/activate")
    public ConditionRuleResponse activateRule(@PathVariable Long id) {
        ConditionRuleEntity entity = conditionRuleJpaRepository
                .findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ConditionRule", String.valueOf(id)));

        entity.setStatus(ConditionRuleStatus.ACTIVE);
        entity.setTriggerCount(0);
        entity.setUpdatedAt(LocalDateTime.now());

        ConditionRuleEntity saved = conditionRuleJpaRepository.save(entity);
        conditionEngine.loadRules();

        return conditionDtoMapper.toResponse(conditionRuleMapper.toDomain(saved));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable Long id) {
        conditionRuleJpaRepository.deleteById(id);
        conditionEngine.loadRules();
    }

    // ========================
    // TRIGGER HISTORY
    // ========================

    @GetMapping("/{id}/history")
    public List<ConditionTriggerHistoryResponse> getTriggerHistory(@PathVariable Long id) {
        List<ConditionTriggerHistory> history = conditionRuleMapper.toHistoryDomainList(
                conditionTriggerHistoryJpaRepository.findByRuleIdOrderByTriggeredAtDesc(id));
        return conditionDtoMapper.toHistoryResponseList(history);
    }

    @GetMapping("/history/recent")
    public List<ConditionTriggerHistoryResponse> getRecentTriggers() {
        List<ConditionTriggerHistory> history = conditionRuleMapper.toHistoryDomainList(
                conditionTriggerHistoryJpaRepository.findTop50ByOrderByTriggeredAtDesc());
        return conditionDtoMapper.toHistoryResponseList(history);
    }

    // ========================
    // COMPOSITE RULES
    // ========================

    @PostMapping("/composites")
    @ResponseStatus(HttpStatus.CREATED)
    public CompositeConditionRuleResponse createCompositeRule(
            @RequestBody @Valid CompositeConditionRuleRequest request) {
        CompositeConditionRule rule = conditionDtoMapper.toDomain(request);
        rule.setStatus(ConditionRuleStatus.ACTIVE);
        rule.setCreatedAt(LocalDateTime.now());

        CompositeConditionRuleEntity entity = conditionRuleMapper.toEntity(rule);
        CompositeConditionRuleEntity saved = compositeConditionRuleJpaRepository.save(entity);

        return conditionDtoMapper.toResponse(conditionRuleMapper.toDomain(saved));
    }

    @GetMapping("/composites")
    public List<CompositeConditionRuleResponse> getAllCompositeRules() {
        List<CompositeConditionRule> rules =
                conditionRuleMapper.toCompositeDomainList(compositeConditionRuleJpaRepository.findAll());
        return conditionDtoMapper.toCompositeResponseList(rules);
    }

    @DeleteMapping("/composites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCompositeRule(@PathVariable Long id) {
        compositeConditionRuleJpaRepository.deleteById(id);
    }
}
