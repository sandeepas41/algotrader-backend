package com.algotrader.unit.morph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.SimpleMorphPlan;
import com.algotrader.entity.StrategyLegEntity;
import com.algotrader.exception.BusinessException;
import com.algotrader.morph.MorphTargetResolver;
import com.algotrader.repository.jpa.StrategyLegJpaRepository;
import com.algotrader.strategy.base.BaseStrategy;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for MorphTargetResolver: validates all 8 morph rules,
 * invalid combinations, and edge cases like empty legs.
 */
@ExtendWith(MockitoExtension.class)
class MorphTargetResolverTest {

    private static final String STRATEGY_ID = "STR-TEST-001";

    @Mock
    private StrategyEngine strategyEngine;

    @Mock
    private StrategyLegJpaRepository strategyLegJpaRepository;

    @Mock
    private BaseStrategy sourceStrategy;

    private MorphTargetResolver morphTargetResolver;

    @BeforeEach
    void setUp() {
        morphTargetResolver = new MorphTargetResolver(strategyEngine, strategyLegJpaRepository);
    }

    // -- Helper to build a StrategyLegEntity --

    private StrategyLegEntity buildLeg(String id, InstrumentType optionType, int quantity) {
        return StrategyLegEntity.builder()
                .id(id)
                .strategyId(STRATEGY_ID)
                .optionType(optionType)
                .strike(BigDecimal.valueOf(24000))
                .quantity(quantity)
                .build();
    }

    /** Standard 4-leg Iron Condor: SELL CE, BUY CE, SELL PE, BUY PE. */
    private List<StrategyLegEntity> ironCondorLegs() {
        return List.of(
                buildLeg("leg-sell-ce", InstrumentType.CE, -1),
                buildLeg("leg-buy-ce", InstrumentType.CE, 1),
                buildLeg("leg-sell-pe", InstrumentType.PE, -1),
                buildLeg("leg-buy-pe", InstrumentType.PE, 1));
    }

    /** Standard 2-leg Straddle: SELL CE, SELL PE. */
    private List<StrategyLegEntity> straddleLegs() {
        return List.of(buildLeg("leg-sell-ce", InstrumentType.CE, -1), buildLeg("leg-sell-pe", InstrumentType.PE, -1));
    }

    /** Standard 2-leg Strangle: SELL CE, SELL PE. */
    private List<StrategyLegEntity> strangleLegs() {
        return List.of(buildLeg("leg-sell-ce", InstrumentType.CE, -1), buildLeg("leg-sell-pe", InstrumentType.PE, -1));
    }

    /** Standard 2-leg Bull Call Spread: BUY CE, SELL CE. */
    private List<StrategyLegEntity> bullCallSpreadLegs() {
        return List.of(buildLeg("leg-buy-ce", InstrumentType.CE, 1), buildLeg("leg-sell-ce", InstrumentType.CE, -1));
    }

    /** Standard 2-leg Bear Put Spread: BUY PE, SELL PE. */
    private List<StrategyLegEntity> bearPutSpreadLegs() {
        return List.of(buildLeg("leg-buy-pe", InstrumentType.PE, 1), buildLeg("leg-sell-pe", InstrumentType.PE, -1));
    }

    private void setupStrategy(StrategyType type, List<StrategyLegEntity> legs) {
        when(strategyEngine.getStrategy(STRATEGY_ID)).thenReturn(sourceStrategy);
        when(sourceStrategy.getType()).thenReturn(type);
        when(strategyLegJpaRepository.findByStrategyId(STRATEGY_ID)).thenReturn(legs);
    }

    @Nested
    @DisplayName("Rule 1: IRON_CONDOR -> BULL_PUT_SPREAD")
    class IronCondorToBullPutSpread {

        @Test
        @DisplayName("keeps put legs, closes call legs, no new legs")
        void resolve_keepsAndCloses() {
            setupStrategy(StrategyType.IRON_CONDOR, ironCondorLegs());

            SimpleMorphPlan plan = morphTargetResolver.resolve(STRATEGY_ID, StrategyType.BULL_PUT_SPREAD);

            assertThat(plan.getTargetType()).isEqualTo(StrategyType.BULL_PUT_SPREAD);
            assertThat(plan.getDescription()).contains("put spread");
            assertThat(plan.getLegsToKeep()).containsExactlyInAnyOrder("leg-sell-pe", "leg-buy-pe");
            assertThat(plan.getLegsToClose()).containsExactlyInAnyOrder("leg-sell-ce", "leg-buy-ce");
            assertThat(plan.getLegsToOpen()).isEmpty();
            assertThat(plan.isRequiresStrikeSelection()).isFalse();
        }
    }

    @Nested
    @DisplayName("Rule 2: IRON_CONDOR -> BEAR_CALL_SPREAD")
    class IronCondorToBearCallSpread {

        @Test
        @DisplayName("keeps call legs, closes put legs, no new legs")
        void resolve_keepsAndCloses() {
            setupStrategy(StrategyType.IRON_CONDOR, ironCondorLegs());

            SimpleMorphPlan plan = morphTargetResolver.resolve(STRATEGY_ID, StrategyType.BEAR_CALL_SPREAD);

            assertThat(plan.getTargetType()).isEqualTo(StrategyType.BEAR_CALL_SPREAD);
            assertThat(plan.getDescription()).contains("call spread");
            assertThat(plan.getLegsToKeep()).containsExactlyInAnyOrder("leg-sell-ce", "leg-buy-ce");
            assertThat(plan.getLegsToClose()).containsExactlyInAnyOrder("leg-sell-pe", "leg-buy-pe");
            assertThat(plan.getLegsToOpen()).isEmpty();
            assertThat(plan.isRequiresStrikeSelection()).isFalse();
        }
    }

    @Nested
    @DisplayName("Rule 3: IRON_CONDOR -> IRON_BUTTERFLY")
    class IronCondorToIronButterfly {

        @Test
        @DisplayName("closes all legs, opens 4 new at ATM, requires strike selection")
        void resolve_fullReplace() {
            setupStrategy(StrategyType.IRON_CONDOR, ironCondorLegs());

            SimpleMorphPlan plan = morphTargetResolver.resolve(STRATEGY_ID, StrategyType.IRON_BUTTERFLY);

            assertThat(plan.getTargetType()).isEqualTo(StrategyType.IRON_BUTTERFLY);
            assertThat(plan.getLegsToKeep()).isEmpty();
            assertThat(plan.getLegsToClose()).hasSize(4);
            assertThat(plan.getLegsToOpen()).hasSize(4);
            assertThat(plan.isRequiresStrikeSelection()).isTrue();
        }
    }

    @Nested
    @DisplayName("Rule 4: STRADDLE -> STRANGLE")
    class StraddleToStrangle {

        @Test
        @DisplayName("closes all legs, opens 2 new OTM, requires strike selection")
        void resolve_fullReplace() {
            setupStrategy(StrategyType.STRADDLE, straddleLegs());

            SimpleMorphPlan plan = morphTargetResolver.resolve(STRATEGY_ID, StrategyType.STRANGLE);

            assertThat(plan.getTargetType()).isEqualTo(StrategyType.STRANGLE);
            assertThat(plan.getLegsToKeep()).isEmpty();
            assertThat(plan.getLegsToClose()).hasSize(2);
            assertThat(plan.getLegsToOpen()).hasSize(2);
            assertThat(plan.isRequiresStrikeSelection()).isTrue();
        }
    }

    @Nested
    @DisplayName("Rule 5: STRANGLE -> STRADDLE")
    class StrangleToStraddle {

        @Test
        @DisplayName("closes all legs, opens 2 new ATM, requires strike selection")
        void resolve_fullReplace() {
            setupStrategy(StrategyType.STRANGLE, strangleLegs());

            SimpleMorphPlan plan = morphTargetResolver.resolve(STRATEGY_ID, StrategyType.STRADDLE);

            assertThat(plan.getTargetType()).isEqualTo(StrategyType.STRADDLE);
            assertThat(plan.getLegsToKeep()).isEmpty();
            assertThat(plan.getLegsToClose()).hasSize(2);
            assertThat(plan.getLegsToOpen()).containsExactly("SELL CE ATM", "SELL PE ATM");
            assertThat(plan.isRequiresStrikeSelection()).isTrue();
        }
    }

    @Nested
    @DisplayName("Rule 6: STRANGLE -> IRON_CONDOR")
    class StrangleToIronCondor {

        @Test
        @DisplayName("keeps short legs, adds protection legs, requires strike selection")
        void resolve_retainAndAdd() {
            setupStrategy(StrategyType.STRANGLE, strangleLegs());

            SimpleMorphPlan plan = morphTargetResolver.resolve(STRATEGY_ID, StrategyType.IRON_CONDOR);

            assertThat(plan.getTargetType()).isEqualTo(StrategyType.IRON_CONDOR);
            assertThat(plan.getLegsToKeep()).containsExactlyInAnyOrder("leg-sell-ce", "leg-sell-pe");
            assertThat(plan.getLegsToClose()).isEmpty();
            assertThat(plan.getLegsToOpen()).hasSize(2);
            assertThat(plan.isRequiresStrikeSelection()).isTrue();
        }
    }

    @Nested
    @DisplayName("Rule 7: BULL_CALL_SPREAD -> IRON_CONDOR")
    class BullCallSpreadToIronCondor {

        @Test
        @DisplayName("keeps call spread, adds put spread, requires strike selection")
        void resolve_retainAndAdd() {
            setupStrategy(StrategyType.BULL_CALL_SPREAD, bullCallSpreadLegs());

            SimpleMorphPlan plan = morphTargetResolver.resolve(STRATEGY_ID, StrategyType.IRON_CONDOR);

            assertThat(plan.getTargetType()).isEqualTo(StrategyType.IRON_CONDOR);
            assertThat(plan.getLegsToKeep()).containsExactlyInAnyOrder("leg-sell-ce", "leg-buy-ce");
            assertThat(plan.getLegsToClose()).isEmpty();
            assertThat(plan.getLegsToOpen()).hasSize(2);
            assertThat(plan.isRequiresStrikeSelection()).isTrue();
        }
    }

    @Nested
    @DisplayName("Rule 8: BEAR_PUT_SPREAD -> IRON_CONDOR")
    class BearPutSpreadToIronCondor {

        @Test
        @DisplayName("keeps put spread, adds call spread, requires strike selection")
        void resolve_retainAndAdd() {
            setupStrategy(StrategyType.BEAR_PUT_SPREAD, bearPutSpreadLegs());

            SimpleMorphPlan plan = morphTargetResolver.resolve(STRATEGY_ID, StrategyType.IRON_CONDOR);

            assertThat(plan.getTargetType()).isEqualTo(StrategyType.IRON_CONDOR);
            assertThat(plan.getLegsToKeep()).containsExactlyInAnyOrder("leg-sell-pe", "leg-buy-pe");
            assertThat(plan.getLegsToClose()).isEmpty();
            assertThat(plan.getLegsToOpen()).hasSize(2);
            assertThat(plan.isRequiresStrikeSelection()).isTrue();
        }
    }

    @Nested
    @DisplayName("Invalid combinations")
    class InvalidCombinations {

        @Test
        @DisplayName("throws for unsupported morph combination")
        void resolve_unsupported_throws() {
            setupStrategy(StrategyType.STRADDLE, straddleLegs());

            assertThatThrownBy(() -> morphTargetResolver.resolve(STRATEGY_ID, StrategyType.BULL_PUT_SPREAD))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Unsupported morph");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("handles strategy with no legs gracefully")
        void resolve_noLegs() {
            setupStrategy(StrategyType.IRON_CONDOR, Collections.emptyList());

            SimpleMorphPlan plan = morphTargetResolver.resolve(STRATEGY_ID, StrategyType.BULL_PUT_SPREAD);

            assertThat(plan.getLegsToKeep()).isEmpty();
            assertThat(plan.getLegsToClose()).isEmpty();
            assertThat(plan.getLegsToOpen()).isEmpty();
            assertThat(plan.isRequiresStrikeSelection()).isFalse();
        }

        @Test
        @DisplayName("toMorphRequest throws for requiresStrikeSelection plans")
        void toMorphRequest_strikeSelectionRequired_throws() {
            setupStrategy(StrategyType.STRADDLE, straddleLegs());
            SimpleMorphPlan plan = morphTargetResolver.resolve(STRATEGY_ID, StrategyType.STRANGLE);

            assertThatThrownBy(() -> morphTargetResolver.toMorphRequest(STRATEGY_ID, plan))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("strike selection");
        }

        @Test
        @DisplayName("toMorphRequest builds valid request for retention-only morphs")
        void toMorphRequest_retentionOnly_buildsRequest() {
            setupStrategy(StrategyType.IRON_CONDOR, ironCondorLegs());
            SimpleMorphPlan plan = morphTargetResolver.resolve(STRATEGY_ID, StrategyType.BULL_PUT_SPREAD);

            var request = morphTargetResolver.toMorphRequest(STRATEGY_ID, plan);

            assertThat(request.getSourceStrategyId()).isEqualTo(STRATEGY_ID);
            assertThat(request.getTargets()).hasSize(1);
            assertThat(request.getTargets().get(0).getStrategyType()).isEqualTo(StrategyType.BULL_PUT_SPREAD);
            assertThat(request.getTargets().get(0).getRetainedLegs()).containsExactlyInAnyOrder("SELL_PE", "BUY_PE");
        }
    }
}
