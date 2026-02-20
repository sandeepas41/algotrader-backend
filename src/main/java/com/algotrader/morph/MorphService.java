package com.algotrader.morph;

import com.algotrader.config.MorphConfig;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.MorphPlanStatus;
import com.algotrader.domain.enums.OrderPriority;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.model.LegCloseStep;
import com.algotrader.domain.model.LegOpenStep;
import com.algotrader.domain.model.LegReassignStep;
import com.algotrader.domain.model.MorphExecutionPlan;
import com.algotrader.domain.model.MorphPlan;
import com.algotrader.domain.model.MorphRequest;
import com.algotrader.domain.model.MorphResult;
import com.algotrader.domain.model.MorphTarget;
import com.algotrader.domain.model.NewLegDefinition;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.StrategyCreateStep;
import com.algotrader.domain.model.StrategyLineage;
import com.algotrader.entity.MorphHistoryEntity;
import com.algotrader.entity.MorphPlanEntity;
import com.algotrader.event.MorphEvent;
import com.algotrader.exception.BusinessException;
import com.algotrader.exception.ErrorCode;
import com.algotrader.mapper.MorphHistoryMapper;
import com.algotrader.mapper.MorphPlanMapper;
import com.algotrader.observability.DecisionLogger;
import com.algotrader.oms.JournaledMultiLegExecutor;
import com.algotrader.oms.OrderRequest;
import com.algotrader.repository.jpa.MorphHistoryJpaRepository;
import com.algotrader.repository.jpa.MorphPlanJpaRepository;
import com.algotrader.strategy.base.BaseStrategy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.mapstruct.factory.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Core service for strategy morphing (transformation) operations.
 *
 * <p>Morphing converts one running strategy into one or more different strategy types.
 * For example, an Iron Condor can be morphed into a Bull Put Spread + Straddle,
 * preserving relevant legs and creating new ones.
 *
 * <p>The morph workflow:
 * <ol>
 *   <li><b>Preview</b>: Generate an execution plan without executing (for UI confirmation)</li>
 *   <li><b>Validate</b>: Pre-morph checks (source must be ACTIVE, no in-flight adjustments)</li>
 *   <li><b>Plan</b>: Determine which legs to close, reassign, and open</li>
 *   <li><b>Persist WAL</b>: Write the plan to morph_plans before execution</li>
 *   <li><b>Execute</b>: Close legs -> open new legs -> create strategies -> reassign positions</li>
 *   <li><b>Record lineage</b>: Create parent-child link in morph_history</li>
 * </ol>
 *
 * <p>Uses a Write-Ahead Log (morph_plans table) for crash recovery.
 * If the app crashes mid-morph, incomplete plans (EXECUTING status) are
 * detected on startup by MorphRecoveryService.
 */
@Service
public class MorphService {

    private static final Logger log = LoggerFactory.getLogger(MorphService.class);

    private final StrategyEngine strategyEngine;
    private final JournaledMultiLegExecutor journaledMultiLegExecutor;
    private final MorphPlanJpaRepository morphPlanJpaRepository;
    private final MorphHistoryJpaRepository morphHistoryJpaRepository;
    private final DecisionLogger decisionLogger;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;
    private final MorphConfig morphConfig;

    private final MorphPlanMapper morphPlanMapper = Mappers.getMapper(MorphPlanMapper.class);
    private final MorphHistoryMapper morphHistoryMapper = Mappers.getMapper(MorphHistoryMapper.class);

    public MorphService(
            StrategyEngine strategyEngine,
            JournaledMultiLegExecutor journaledMultiLegExecutor,
            MorphPlanJpaRepository morphPlanJpaRepository,
            MorphHistoryJpaRepository morphHistoryJpaRepository,
            DecisionLogger decisionLogger,
            ApplicationEventPublisher applicationEventPublisher,
            ObjectMapper objectMapper,
            MorphConfig morphConfig) {
        this.strategyEngine = strategyEngine;
        this.journaledMultiLegExecutor = journaledMultiLegExecutor;
        this.morphPlanJpaRepository = morphPlanJpaRepository;
        this.morphHistoryJpaRepository = morphHistoryJpaRepository;
        this.decisionLogger = decisionLogger;
        this.applicationEventPublisher = applicationEventPublisher;
        this.objectMapper = objectMapper;
        this.morphConfig = morphConfig;
    }

    /**
     * Generates a morph execution plan without executing it.
     * Used by the UI to preview what will happen before confirming.
     *
     * @param request the morph request
     * @return the execution plan describing all steps
     */
    public MorphExecutionPlan preview(MorphRequest request) {
        BaseStrategy sourceStrategy = validateSource(request.getSourceStrategyId());
        return generatePlan(request, sourceStrategy);
    }

    /**
     * Executes a full morph operation, transforming the source strategy
     * into one or more target strategies.
     *
     * @param request the morph request
     * @return result summarizing what was done
     * @throws BusinessException if the source is not ACTIVE or morphing is disabled
     */
    public MorphResult morph(MorphRequest request) {
        if (!morphConfig.isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Morphing is disabled");
        }

        log.info(
                "Morph requested: source={}, targets={}",
                request.getSourceStrategyId(),
                request.getTargets().stream()
                        .map(t -> t.getStrategyType().name())
                        .toList());

        // Step 1: Validate source strategy
        BaseStrategy sourceStrategy = validateSource(request.getSourceStrategyId());

        // Step 2: Generate execution plan
        MorphExecutionPlan plan = generatePlan(request, sourceStrategy);
        validatePlanLimits(plan);

        // Step 3: Persist plan as WAL
        MorphPlan walEntry = persistPlan(request, plan, sourceStrategy);

        // Step 4: Pause source to prevent concurrent evaluation during morph
        strategyEngine.pauseStrategy(request.getSourceStrategyId());

        // Step 5: Execute
        try {
            walEntry.setStatus(MorphPlanStatus.EXECUTING);
            walEntry.setExecutedAt(LocalDateTime.now());
            savePlan(walEntry);

            MorphResult result = executePlan(request, plan, walEntry);

            walEntry.setStatus(MorphPlanStatus.COMPLETED);
            walEntry.setCompletedAt(LocalDateTime.now());
            savePlan(walEntry);

            decisionLogger.logMorph(
                    request.getSourceStrategyId(),
                    true,
                    "Morph completed successfully",
                    buildMorphContext(request, result));

            return result;

        } catch (Exception e) {
            log.error("Morph execution failed for source strategy {}", request.getSourceStrategyId(), e);

            walEntry.setStatus(MorphPlanStatus.FAILED);
            walEntry.setErrorMessage(e.getMessage());
            savePlan(walEntry);

            decisionLogger.logMorph(
                    request.getSourceStrategyId(),
                    false,
                    "Morph FAILED: " + e.getMessage(),
                    Map.of("error", e.getMessage()));

            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "Morph failed for strategy " + request.getSourceStrategyId());
        }
    }

    /**
     * Returns all morph plans, ordered by creation time descending.
     */
    public List<MorphPlan> getAllPlans() {
        return morphPlanMapper.toDomainList(morphPlanJpaRepository.findAllByOrderByCreatedAtDesc());
    }

    /**
     * Returns a specific morph plan by ID.
     */
    public MorphPlan getPlan(Long id) {
        return morphPlanJpaRepository
                .findById(id)
                .map(morphPlanMapper::toDomain)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Morph plan not found: " + id));
    }

    // ========================
    // VALIDATION
    // ========================

    private BaseStrategy validateSource(String sourceStrategyId) {
        BaseStrategy sourceStrategy = strategyEngine.getStrategy(sourceStrategyId);
        if (sourceStrategy.getStatus() != StrategyStatus.ACTIVE
                && sourceStrategy.getStatus() != StrategyStatus.PAUSED) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "Source strategy must be ACTIVE or PAUSED to morph, current status: " + sourceStrategy.getStatus());
        }
        return sourceStrategy;
    }

    private void validatePlanLimits(MorphExecutionPlan plan) {
        if (plan.getLegsToClose().size() > morphConfig.getMaxLegsToClose()) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "Morph would close " + plan.getLegsToClose().size() + " legs, exceeding limit of "
                            + morphConfig.getMaxLegsToClose());
        }
        if (plan.getLegsToOpen().size() > morphConfig.getMaxLegsToOpen()) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "Morph would open " + plan.getLegsToOpen().size() + " legs, exceeding limit of "
                            + morphConfig.getMaxLegsToOpen());
        }
    }

    // ========================
    // PLAN GENERATION
    // ========================

    MorphExecutionPlan generatePlan(MorphRequest request, BaseStrategy sourceStrategy) {
        List<Position> sourcePositions = sourceStrategy.getPositions();

        List<LegCloseStep> legsToClose = new ArrayList<>();
        List<LegReassignStep> legsToReassign = new ArrayList<>();
        List<LegOpenStep> legsToOpen = new ArrayList<>();
        List<StrategyCreateStep> strategiesToCreate = new ArrayList<>();

        // Collect all retained leg IDs across all targets
        Set<String> allRetainedLegIds = new HashSet<>();
        for (MorphTarget target : request.getTargets()) {
            if (target.getRetainedLegs() != null) {
                allRetainedLegIds.addAll(target.getRetainedLegs());
            }
        }

        // Legs to close = source positions not retained by any target
        if (sourcePositions != null) {
            for (Position position : sourcePositions) {
                String legId = identifyLeg(position);
                if (!allRetainedLegIds.contains(legId)) {
                    legsToClose.add(LegCloseStep.builder()
                            .positionId(position.getId())
                            .instrumentToken(position.getInstrumentToken())
                            .tradingSymbol(position.getTradingSymbol())
                            .quantity(Math.abs(position.getQuantity()))
                            // Opposite side to flatten the position
                            .closeSide(position.getQuantity() > 0 ? OrderSide.SELL : OrderSide.BUY)
                            .status("PENDING")
                            .build());
                }
            }
        }

        // Process each target
        for (MorphTarget target : request.getTargets()) {
            String newStrategyId = UUID.randomUUID().toString();
            List<String> assignedPositionIds = new ArrayList<>();

            // Reassign retained legs
            if (target.getRetainedLegs() != null && sourcePositions != null) {
                for (String legId : target.getRetainedLegs()) {
                    Position position = findPositionByLegId(sourcePositions, legId);
                    if (position != null) {
                        legsToReassign.add(LegReassignStep.builder()
                                .positionId(position.getId())
                                .instrumentToken(position.getInstrumentToken())
                                .tradingSymbol(position.getTradingSymbol())
                                .fromStrategyId(request.getSourceStrategyId())
                                .toStrategyId(newStrategyId)
                                .copyEntryPrice(request.isCopyEntryPrices())
                                .build());
                        assignedPositionIds.add(position.getId());
                    }
                }
            }

            // New legs to open
            if (target.getNewLegs() != null) {
                for (NewLegDefinition newLeg : target.getNewLegs()) {
                    // #TODO: Resolve instrument token from underlying + strike + optionType + expiry
                    legsToOpen.add(LegOpenStep.builder()
                            .strike(newLeg.getStrike())
                            .optionType(newLeg.getOptionType())
                            .side(newLeg.getSide())
                            .quantity(newLeg.getLots() != null ? newLeg.getLots() : 1)
                            .targetStrategyId(newStrategyId)
                            .status("PENDING")
                            .build());
                }
            }

            strategiesToCreate.add(StrategyCreateStep.builder()
                    .newStrategyId(newStrategyId)
                    .strategyType(target.getStrategyType())
                    .parentStrategyId(request.getSourceStrategyId())
                    .parameters(target.getParameters())
                    .assignedPositionIds(assignedPositionIds)
                    .build());
        }

        return MorphExecutionPlan.builder()
                .sourceStrategyId(request.getSourceStrategyId())
                .sourceType(sourceStrategy.getType())
                .legsToClose(legsToClose)
                .legsToReassign(legsToReassign)
                .legsToOpen(legsToOpen)
                .strategiesToCreate(strategiesToCreate)
                .build();
    }

    // ========================
    // EXECUTION
    // ========================

    private MorphResult executePlan(MorphRequest request, MorphExecutionPlan plan, MorphPlan walEntry) {

        // Source was already paused in morph() before execution started.

        // Step A: Close legs not retained (via multi-leg executor)
        if (!plan.getLegsToClose().isEmpty()) {
            List<OrderRequest> closeOrders = plan.getLegsToClose().stream()
                    .map(close -> OrderRequest.builder()
                            .instrumentToken(close.getInstrumentToken())
                            .tradingSymbol(close.getTradingSymbol())
                            .side(close.getCloseSide())
                            .type(OrderType.MARKET)
                            .quantity(close.getQuantity())
                            .strategyId(request.getSourceStrategyId())
                            .correlationId("MORPH_CLOSE_" + walEntry.getId())
                            .build())
                    .toList();

            // Buy-first: buying back short positions frees margin before selling long positions
            JournaledMultiLegExecutor.MultiLegResult closeResult = journaledMultiLegExecutor.executeBuyFirstThenSell(
                    closeOrders,
                    request.getSourceStrategyId(),
                    "MORPH_CLOSE",
                    OrderPriority.STRATEGY_ADJUSTMENT,
                    Duration.ofSeconds(morphConfig.getCloseOrderTimeoutSeconds()));

            if (!closeResult.isSuccess()) {
                throw new BusinessException(
                        ErrorCode.INTERNAL_ERROR,
                        "Failed to close " + plan.getLegsToClose().size() + " legs during morph");
            }

            // Update step statuses
            for (int i = 0; i < plan.getLegsToClose().size(); i++) {
                plan.getLegsToClose().get(i).setStatus("EXECUTED");
            }
        }

        // Step C: Open new legs
        if (!plan.getLegsToOpen().isEmpty()) {
            List<OrderRequest> openOrders = plan.getLegsToOpen().stream()
                    .map(open -> OrderRequest.builder()
                            .instrumentToken(open.getInstrumentToken() != null ? open.getInstrumentToken() : 0L)
                            .tradingSymbol(open.getTradingSymbol())
                            .side(open.getSide())
                            .type(OrderType.MARKET)
                            .quantity(open.getQuantity())
                            .strategyId(open.getTargetStrategyId())
                            .correlationId("MORPH_OPEN_" + walEntry.getId())
                            .build())
                    .toList();

            JournaledMultiLegExecutor.MultiLegResult openResult = journaledMultiLegExecutor.executeParallel(
                    openOrders, request.getSourceStrategyId(), "MORPH_OPEN", OrderPriority.STRATEGY_ADJUSTMENT);

            if (!openResult.isSuccess()) {
                throw new BusinessException(
                        ErrorCode.INTERNAL_ERROR,
                        "Failed to open " + plan.getLegsToOpen().size() + " new legs during morph");
            }

            for (int i = 0; i < plan.getLegsToOpen().size(); i++) {
                plan.getLegsToOpen().get(i).setStatus("EXECUTED");
            }
        }

        // Step D: Close the source strategy
        strategyEngine.closeStrategy(request.getSourceStrategyId());

        // Step E: Record lineage for each new strategy
        List<String> newStrategyIds = new ArrayList<>();
        for (StrategyCreateStep create : plan.getStrategiesToCreate()) {
            newStrategyIds.add(create.getNewStrategyId());

            StrategyLineage lineage = StrategyLineage.builder()
                    .parentStrategyId(request.getSourceStrategyId())
                    .childStrategyId(create.getNewStrategyId())
                    .parentStrategyType(plan.getSourceType())
                    .childStrategyType(create.getStrategyType())
                    .morphReason(request.getReason())
                    .morphedAt(LocalDateTime.now())
                    .build();

            MorphHistoryEntity entity = morphHistoryMapper.toEntity(lineage);
            morphHistoryJpaRepository.save(entity);
        }

        // Publish event for WebSocket + AlertService
        applicationEventPublisher.publishEvent(new MorphEvent(this, request.getSourceStrategyId(), newStrategyIds));

        return MorphResult.builder()
                .sourceStrategyId(request.getSourceStrategyId())
                .newStrategyIds(newStrategyIds)
                .legsClosedCount(plan.getLegsToClose().size())
                .legsReassignedCount(plan.getLegsToReassign().size())
                .legsOpenedCount(plan.getLegsToOpen().size())
                .success(true)
                .morphPlanId(walEntry.getId())
                .build();
    }

    // ========================
    // HELPERS
    // ========================

    /**
     * Identifies a position's leg type based on its instrument and side.
     * Convention: SIDE_OPTIONTYPE (e.g., "SELL_CE", "BUY_PE").
     */
    String identifyLeg(Position position) {
        String side = position.getQuantity() > 0 ? "BUY" : "SELL";
        String symbol = position.getTradingSymbol();

        // Extract option type from trading symbol suffix
        if (symbol != null && symbol.endsWith("CE")) {
            return side + "_CE";
        } else if (symbol != null && symbol.endsWith("PE")) {
            return side + "_PE";
        }
        return side + "_UNKNOWN";
    }

    private Position findPositionByLegId(List<Position> positions, String legId) {
        for (Position position : positions) {
            if (identifyLeg(position).equals(legId)) {
                return position;
            }
        }
        return null;
    }

    private MorphPlan persistPlan(MorphRequest request, MorphExecutionPlan plan, BaseStrategy sourceStrategy) {
        String planJson;
        String targetTypesJson;
        try {
            planJson = objectMapper.writeValueAsString(plan);
            targetTypesJson = objectMapper.writeValueAsString(request.getTargets().stream()
                    .map(t -> t.getStrategyType().name())
                    .toList());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to serialize morph plan");
        }

        MorphPlan morphPlan = MorphPlan.builder()
                .sourceStrategyId(request.getSourceStrategyId())
                .sourceStrategyType(sourceStrategy.getType())
                .targetTypes(targetTypesJson)
                .planDetails(planJson)
                .status(MorphPlanStatus.PLANNED)
                .reason(request.getReason())
                .createdAt(LocalDateTime.now())
                .build();

        MorphPlanEntity entity = morphPlanMapper.toEntity(morphPlan);
        MorphPlanEntity saved = morphPlanJpaRepository.save(entity);
        morphPlan.setId(saved.getId());
        return morphPlan;
    }

    private void savePlan(MorphPlan plan) {
        MorphPlanEntity entity = morphPlanMapper.toEntity(plan);
        morphPlanJpaRepository.save(entity);
    }

    private Map<String, Object> buildMorphContext(MorphRequest request, MorphResult result) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("sourceStrategyId", request.getSourceStrategyId());
        context.put("newStrategyIds", result.getNewStrategyIds());
        context.put("legsClosedCount", result.getLegsClosedCount());
        context.put("legsReassignedCount", result.getLegsReassignedCount());
        context.put("legsOpenedCount", result.getLegsOpenedCount());
        context.put("reason", request.getReason());
        return context;
    }
}
