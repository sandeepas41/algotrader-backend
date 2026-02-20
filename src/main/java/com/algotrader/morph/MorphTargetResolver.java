package com.algotrader.morph;

import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.MorphRequest;
import com.algotrader.domain.model.MorphTarget;
import com.algotrader.domain.model.SimpleMorphPlan;
import com.algotrader.domain.vo.ChargeBreakdown;
import com.algotrader.entity.StrategyLegEntity;
import com.algotrader.exception.BusinessException;
import com.algotrader.exception.ErrorCode;
import com.algotrader.repository.jpa.StrategyLegJpaRepository;
import com.algotrader.strategy.base.BaseStrategy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Resolves morph plans from a simple (strategyId, targetType) request.
 *
 * <p>Encodes the business rules for which legs to retain, close, and open
 * for each valid source-to-target morph combination. This bridges the gap
 * between the FE's simple morph request and the BE's detailed {@link MorphRequest}.
 *
 * <p>Used by the simplified morph endpoints on StrategyController to auto-resolve
 * the morph plan without requiring the caller to specify individual legs.
 *
 * <p>Supported morph rules:
 * <ol>
 *   <li>IRON_CONDOR → BULL_PUT_SPREAD (close calls, keep puts)</li>
 *   <li>IRON_CONDOR → BEAR_CALL_SPREAD (close puts, keep calls)</li>
 *   <li>IRON_CONDOR → IRON_BUTTERFLY (close all, open ATM)</li>
 *   <li>STRADDLE → STRANGLE (close ATM, open OTM)</li>
 *   <li>STRANGLE → STRADDLE (close OTM, open ATM)</li>
 *   <li>STRANGLE → IRON_CONDOR (keep shorts, add protection)</li>
 *   <li>BULL_CALL_SPREAD → IRON_CONDOR (keep calls, add put spread)</li>
 *   <li>BEAR_PUT_SPREAD → IRON_CONDOR (keep puts, add call spread)</li>
 * </ol>
 */
@Service
public class MorphTargetResolver {

    private final StrategyEngine strategyEngine;
    private final StrategyLegJpaRepository strategyLegJpaRepository;

    public MorphTargetResolver(StrategyEngine strategyEngine, StrategyLegJpaRepository strategyLegJpaRepository) {
        this.strategyEngine = strategyEngine;
        this.strategyLegJpaRepository = strategyLegJpaRepository;
    }

    /**
     * Resolve a simplified morph plan from a strategy ID and target type.
     *
     * @param strategyId the source strategy ID
     * @param targetType the desired target strategy type
     * @return a SimpleMorphPlan with legs classified into keep/close/open
     * @throws BusinessException if the morph combination is not supported
     */
    public SimpleMorphPlan resolve(String strategyId, StrategyType targetType) {
        BaseStrategy strategy = strategyEngine.getStrategy(strategyId);
        StrategyType sourceType = strategy.getType();

        List<StrategyLegEntity> legs = strategyLegJpaRepository.findByStrategyId(strategyId);

        // Classify each leg as SIDE_OPTIONTYPE (e.g., SELL_CE, BUY_PE)
        // Key: legEntityId, Value: classification string
        Map<String, String> legClassification = classifyLegs(legs);

        String morphKey = sourceType.name() + "->" + targetType.name();

        return switch (morphKey) {
            case "IRON_CONDOR->BULL_PUT_SPREAD" ->
                buildRetainClosePlan(
                        targetType, legClassification, Set.of("SELL_PE", "BUY_PE"), "Close call side, keep put spread");

            case "IRON_CONDOR->BEAR_CALL_SPREAD" ->
                buildRetainClosePlan(
                        targetType, legClassification, Set.of("SELL_CE", "BUY_CE"), "Close put side, keep call spread");

            case "IRON_CONDOR->IRON_BUTTERFLY" ->
                buildFullReplacePlan(
                        targetType,
                        legClassification,
                        "Close all legs and open new at ATM strikes",
                        List.of("SELL CE ATM", "BUY CE OTM", "SELL PE ATM", "BUY PE OTM"));

            case "STRADDLE->STRANGLE" ->
                buildFullReplacePlan(
                        targetType,
                        legClassification,
                        "Move strikes away from ATM",
                        List.of("SELL CE OTM", "SELL PE OTM"));

            case "STRANGLE->STRADDLE" ->
                buildFullReplacePlan(
                        targetType, legClassification, "Move strikes to ATM", List.of("SELL CE ATM", "SELL PE ATM"));

            case "STRANGLE->IRON_CONDOR" ->
                buildRetainAndAddPlan(
                        targetType,
                        legClassification,
                        Set.of("SELL_CE", "SELL_PE"),
                        "Add protection legs to form iron condor",
                        List.of("BUY CE OTM (protection)", "BUY PE OTM (protection)"));

            case "BULL_CALL_SPREAD->IRON_CONDOR" ->
                buildRetainAndAddPlan(
                        targetType,
                        legClassification,
                        Set.of("SELL_CE", "BUY_CE"),
                        "Add put spread to form iron condor",
                        List.of("SELL PE OTM", "BUY PE OTM (protection)"));

            case "BEAR_PUT_SPREAD->IRON_CONDOR" ->
                buildRetainAndAddPlan(
                        targetType,
                        legClassification,
                        Set.of("SELL_PE", "BUY_PE"),
                        "Add call spread to form iron condor",
                        List.of("SELL CE OTM", "BUY CE OTM (protection)"));

            default ->
                throw new BusinessException(
                        ErrorCode.BAD_REQUEST,
                        "Unsupported morph: " + sourceType + " -> " + targetType
                                + ". No auto-resolution rule available.");
        };
    }

    /**
     * Convert a resolved plan into a full {@link MorphRequest} for execution.
     * Only works for plans that don't require strike selection (rules 1-2).
     *
     * @param strategyId the source strategy ID
     * @param plan the resolved plan
     * @return a full MorphRequest suitable for {@link MorphService#morph(MorphRequest)}
     * @throws BusinessException if the plan requires strike selection
     */
    public MorphRequest toMorphRequest(String strategyId, SimpleMorphPlan plan) {
        if (plan.isRequiresStrikeSelection()) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "This morph requires strike selection. Use the advanced morph API with explicit leg definitions.");
        }

        // For retention-only morphs, build a target with retained leg identifiers
        List<StrategyLegEntity> legs = strategyLegJpaRepository.findByStrategyId(strategyId);
        Map<String, String> legClassification = classifyLegs(legs);

        // Collect retained leg type identifiers (SELL_PE, BUY_PE, etc.)
        List<String> retainedLegTypes = new ArrayList<>();
        for (Map.Entry<String, String> entry : legClassification.entrySet()) {
            if (plan.getLegsToKeep().contains(entry.getKey())) {
                retainedLegTypes.add(entry.getValue());
            }
        }

        MorphTarget target = MorphTarget.builder()
                .strategyType(plan.getTargetType())
                .retainedLegs(retainedLegTypes)
                .newLegs(Collections.emptyList())
                .build();

        return MorphRequest.builder()
                .sourceStrategyId(strategyId)
                .targets(List.of(target))
                .copyEntryPrices(true)
                .autoArm(false)
                .reason("Simple morph to " + plan.getTargetType())
                .build();
    }

    /**
     * Classify each strategy leg as SIDE_OPTIONTYPE (e.g., SELL_CE, BUY_PE).
     * Uses the leg entity's optionType and quantity sign (positive = BUY, negative = SELL).
     */
    Map<String, String> classifyLegs(List<StrategyLegEntity> legs) {
        Map<String, String> classification = new LinkedHashMap<>();
        for (StrategyLegEntity leg : legs) {
            String side = leg.getQuantity() >= 0 ? "BUY" : "SELL";
            String optionType =
                    leg.getOptionType() != null ? leg.getOptionType().name() : "UNKNOWN";
            classification.put(leg.getId(), side + "_" + optionType);
        }
        return classification;
    }

    /**
     * Build a plan that retains some legs and closes the rest. No new legs needed.
     * Used for rules 1-2 (IRON_CONDOR to spreads).
     */
    private SimpleMorphPlan buildRetainClosePlan(
            StrategyType targetType,
            Map<String, String> legClassification,
            Set<String> retainTypes,
            String description) {

        List<String> keep = new ArrayList<>();
        List<String> close = new ArrayList<>();

        for (Map.Entry<String, String> entry : legClassification.entrySet()) {
            if (retainTypes.contains(entry.getValue())) {
                keep.add(entry.getKey());
            } else {
                close.add(entry.getKey());
            }
        }

        return SimpleMorphPlan.builder()
                .targetType(targetType)
                .description(description)
                .legsToKeep(keep)
                .legsToClose(close)
                .legsToOpen(Collections.emptyList())
                .requiresStrikeSelection(false)
                .estimatedCost(BigDecimal.ZERO)
                .estimatedCharges(ChargeBreakdown.zero())
                .build();
    }

    /**
     * Build a plan that closes all legs and opens new ones. Requires strike selection.
     * Used for rules 3-5 (full replacement morphs like STRADDLE to STRANGLE).
     */
    private SimpleMorphPlan buildFullReplacePlan(
            StrategyType targetType,
            Map<String, String> legClassification,
            String description,
            List<String> newLegDescriptions) {

        List<String> close = new ArrayList<>(legClassification.keySet());

        return SimpleMorphPlan.builder()
                .targetType(targetType)
                .description(description)
                .legsToKeep(Collections.emptyList())
                .legsToClose(close)
                .legsToOpen(newLegDescriptions)
                .requiresStrikeSelection(true)
                .estimatedCost(BigDecimal.ZERO)
                .estimatedCharges(ChargeBreakdown.zero())
                .build();
    }

    /**
     * Build a plan that retains some legs and adds new ones. Requires strike selection.
     * Used for rules 6-8 (add protection/spread legs).
     */
    private SimpleMorphPlan buildRetainAndAddPlan(
            StrategyType targetType,
            Map<String, String> legClassification,
            Set<String> retainTypes,
            String description,
            List<String> newLegDescriptions) {

        List<String> keep = new ArrayList<>();
        List<String> close = new ArrayList<>();

        for (Map.Entry<String, String> entry : legClassification.entrySet()) {
            if (retainTypes.contains(entry.getValue())) {
                keep.add(entry.getKey());
            } else {
                // Legs not matching retain types are closed
                close.add(entry.getKey());
            }
        }

        return SimpleMorphPlan.builder()
                .targetType(targetType)
                .description(description)
                .legsToKeep(keep)
                .legsToClose(close)
                .legsToOpen(newLegDescriptions)
                .requiresStrikeSelection(true)
                .estimatedCost(BigDecimal.ZERO)
                .estimatedCharges(ChargeBreakdown.zero())
                .build();
    }
}
