package com.algotrader.unit.morph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.config.MorphConfig;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.StrategyStatus;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.LegCloseStep;
import com.algotrader.domain.model.MorphExecutionPlan;
import com.algotrader.domain.model.MorphRequest;
import com.algotrader.domain.model.MorphResult;
import com.algotrader.domain.model.MorphTarget;
import com.algotrader.domain.model.NewLegDefinition;
import com.algotrader.domain.model.Position;
import com.algotrader.entity.MorphHistoryEntity;
import com.algotrader.entity.MorphPlanEntity;
import com.algotrader.exception.BusinessException;
import com.algotrader.morph.MorphService;
import com.algotrader.observability.DecisionLogger;
import com.algotrader.oms.JournaledMultiLegExecutor;
import com.algotrader.repository.jpa.MorphHistoryJpaRepository;
import com.algotrader.repository.jpa.MorphPlanJpaRepository;
import com.algotrader.strategy.base.BaseStrategy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for MorphService: plan generation, validation, execution flow,
 * and error handling.
 */
@ExtendWith(MockitoExtension.class)
class MorphServiceTest {

    @Mock
    private StrategyEngine strategyEngine;

    @Mock
    private JournaledMultiLegExecutor journaledMultiLegExecutor;

    @Mock
    private MorphPlanJpaRepository morphPlanJpaRepository;

    @Mock
    private MorphHistoryJpaRepository morphHistoryJpaRepository;

    @Mock
    private DecisionLogger decisionLogger;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private BaseStrategy sourceStrategy;

    private MorphConfig morphConfig;
    private ObjectMapper objectMapper;
    private MorphService morphService;

    @BeforeEach
    void setUp() {
        morphConfig = new MorphConfig();
        objectMapper = new ObjectMapper();
        morphService = new MorphService(
                strategyEngine,
                journaledMultiLegExecutor,
                morphPlanJpaRepository,
                morphHistoryJpaRepository,
                decisionLogger,
                applicationEventPublisher,
                objectMapper,
                morphConfig);
    }

    // ========================
    // PREVIEW
    // ========================

    @Nested
    @DisplayName("preview()")
    class Preview {

        @Test
        @DisplayName("should generate plan with correct legs to close and reassign")
        void previewPlan() {
            // Source: Iron Condor with 4 legs
            String sourceId = "IC-001";
            Position sellCe = buildPosition("P1", "NIFTY24FEB24300CE", -50, 256001L);
            Position buyCe = buildPosition("P2", "NIFTY24FEB24400CE", 50, 256002L);
            Position sellPe = buildPosition("P3", "NIFTY24FEB24100PE", -50, 256003L);
            Position buyPe = buildPosition("P4", "NIFTY24FEB24000PE", 50, 256004L);

            when(strategyEngine.getStrategy(sourceId)).thenReturn(sourceStrategy);
            when(sourceStrategy.getStatus()).thenReturn(StrategyStatus.ACTIVE);
            when(sourceStrategy.getType()).thenReturn(StrategyType.IRON_CONDOR);
            when(sourceStrategy.getPositions()).thenReturn(List.of(sellCe, buyCe, sellPe, buyPe));

            MorphRequest request = MorphRequest.builder()
                    .sourceStrategyId(sourceId)
                    .targets(List.of(
                            MorphTarget.builder()
                                    .strategyType(StrategyType.BULL_PUT_SPREAD)
                                    .retainedLegs(List.of("SELL_PE", "BUY_PE"))
                                    .build(),
                            MorphTarget.builder()
                                    .strategyType(StrategyType.STRADDLE)
                                    .newLegs(List.of(
                                            NewLegDefinition.builder()
                                                    .strike(new BigDecimal("24500"))
                                                    .optionType(InstrumentType.CE)
                                                    .side(OrderSide.SELL)
                                                    .lots(1)
                                                    .build(),
                                            NewLegDefinition.builder()
                                                    .strike(new BigDecimal("24500"))
                                                    .optionType(InstrumentType.PE)
                                                    .side(OrderSide.SELL)
                                                    .lots(1)
                                                    .build()))
                                    .build()))
                    .copyEntryPrices(true)
                    .reason("NIFTY broke above IC short call")
                    .build();

            MorphExecutionPlan plan = morphService.preview(request);

            // Close call legs (SELL_CE, BUY_CE not retained)
            assertThat(plan.getLegsToClose()).hasSize(2);
            // Reassign put legs to BPS
            assertThat(plan.getLegsToReassign()).hasSize(2);
            // Open 2 new straddle legs
            assertThat(plan.getLegsToOpen()).hasSize(2);
            // Create 2 new strategies
            assertThat(plan.getStrategiesToCreate()).hasSize(2);
            assertThat(plan.getSourceType()).isEqualTo(StrategyType.IRON_CONDOR);
        }

        @Test
        @DisplayName("should throw when source is not ACTIVE or PAUSED")
        void previewRejectsClosedStrategy() {
            String sourceId = "IC-002";
            when(strategyEngine.getStrategy(sourceId)).thenReturn(sourceStrategy);
            when(sourceStrategy.getStatus()).thenReturn(StrategyStatus.CREATED);

            MorphRequest request = MorphRequest.builder()
                    .sourceStrategyId(sourceId)
                    .targets(List.of(MorphTarget.builder()
                            .strategyType(StrategyType.STRADDLE)
                            .build()))
                    .build();

            assertThatThrownBy(() -> morphService.preview(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ACTIVE or PAUSED");
        }
    }

    // ========================
    // PLAN GENERATION
    // ========================

    @Nested
    @DisplayName("generatePlan()")
    class PlanGeneration {

        @Test
        @DisplayName("should identify legs correctly — retaining PE legs closes only CE legs")
        void legIdentification() {
            String sourceId = "IC-001";
            Position sellCe = buildPosition("P1", "NIFTY24FEB24300CE", -50, 256001L);
            Position buyCe = buildPosition("P2", "NIFTY24FEB24400CE", 50, 256002L);
            Position sellPe = buildPosition("P3", "NIFTY24FEB24100PE", -50, 256003L);
            Position buyPe = buildPosition("P4", "NIFTY24FEB24000PE", 50, 256004L);

            when(strategyEngine.getStrategy(sourceId)).thenReturn(sourceStrategy);
            when(sourceStrategy.getStatus()).thenReturn(StrategyStatus.ACTIVE);
            when(sourceStrategy.getType()).thenReturn(StrategyType.IRON_CONDOR);
            when(sourceStrategy.getPositions()).thenReturn(List.of(sellCe, buyCe, sellPe, buyPe));

            MorphRequest request = MorphRequest.builder()
                    .sourceStrategyId(sourceId)
                    .targets(List.of(MorphTarget.builder()
                            .strategyType(StrategyType.BULL_PUT_SPREAD)
                            .retainedLegs(List.of("SELL_PE", "BUY_PE"))
                            .build()))
                    .build();

            MorphExecutionPlan plan = morphService.preview(request);

            // CE legs not retained → closed; PE legs retained → reassigned
            assertThat(plan.getLegsToClose()).hasSize(2);
            assertThat(plan.getLegsToClose())
                    .extracting(LegCloseStep::getPositionId)
                    .containsExactlyInAnyOrder("P1", "P2");
            assertThat(plan.getLegsToReassign()).hasSize(2);
            assertThat(plan.getLegsToReassign())
                    .extracting(leg -> leg.getPositionId())
                    .containsExactlyInAnyOrder("P3", "P4");
        }

        @Test
        @DisplayName("should set close side as opposite of position side")
        void closeSideIsOpposite() {
            String sourceId = "IC-001";
            Position sellCe = buildPosition("P1", "NIFTY24FEB24300CE", -50, 256001L);

            when(strategyEngine.getStrategy(sourceId)).thenReturn(sourceStrategy);
            when(sourceStrategy.getStatus()).thenReturn(StrategyStatus.ACTIVE);
            when(sourceStrategy.getType()).thenReturn(StrategyType.IRON_CONDOR);
            when(sourceStrategy.getPositions()).thenReturn(List.of(sellCe));

            MorphRequest request = MorphRequest.builder()
                    .sourceStrategyId(sourceId)
                    .targets(List.of(MorphTarget.builder()
                            .strategyType(StrategyType.STRADDLE)
                            .build()))
                    .build();

            MorphExecutionPlan plan = morphService.preview(request);

            // Short position (qty=-50) should close with BUY
            LegCloseStep closeStep = plan.getLegsToClose().get(0);
            assertThat(closeStep.getCloseSide()).isEqualTo(OrderSide.BUY);
            assertThat(closeStep.getQuantity()).isEqualTo(50);
        }
    }

    // ========================
    // MORPH EXECUTION
    // ========================

    @Nested
    @DisplayName("morph()")
    class MorphExecution {

        @Test
        @DisplayName("should reject morph when morphing is disabled")
        void disabledMorph() {
            morphConfig.setEnabled(false);

            MorphRequest request = MorphRequest.builder()
                    .sourceStrategyId("IC-001")
                    .targets(List.of(MorphTarget.builder()
                            .strategyType(StrategyType.STRADDLE)
                            .build()))
                    .build();

            assertThatThrownBy(() -> morphService.morph(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("disabled");
        }

        @Test
        @DisplayName("should reject morph when legs exceed limit")
        void exceedsLegLimit() {
            morphConfig.setMaxLegsToClose(1);

            String sourceId = "IC-001";
            Position p1 = buildPosition("P1", "NIFTY24FEB24300CE", -50, 256001L);
            Position p2 = buildPosition("P2", "NIFTY24FEB24400CE", 50, 256002L);

            when(strategyEngine.getStrategy(sourceId)).thenReturn(sourceStrategy);
            when(sourceStrategy.getStatus()).thenReturn(StrategyStatus.ACTIVE);
            when(sourceStrategy.getType()).thenReturn(StrategyType.IRON_CONDOR);
            when(sourceStrategy.getPositions()).thenReturn(List.of(p1, p2));

            MorphRequest request = MorphRequest.builder()
                    .sourceStrategyId(sourceId)
                    .targets(List.of(MorphTarget.builder()
                            .strategyType(StrategyType.STRADDLE)
                            .build()))
                    .build();

            assertThatThrownBy(() -> morphService.morph(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("exceeding limit");
        }

        @Test
        @DisplayName("should execute morph successfully with no legs to close or open")
        void successfulMorphReassignOnly() {
            String sourceId = "IC-001";
            Position sellPe = buildPosition("P1", "NIFTY24FEB24100PE", -50, 256003L);
            Position buyPe = buildPosition("P2", "NIFTY24FEB24000PE", 50, 256004L);

            when(strategyEngine.getStrategy(sourceId)).thenReturn(sourceStrategy);
            when(sourceStrategy.getStatus()).thenReturn(StrategyStatus.ACTIVE);
            when(sourceStrategy.getType()).thenReturn(StrategyType.IRON_CONDOR);
            when(sourceStrategy.getPositions()).thenReturn(List.of(sellPe, buyPe));

            // WAL save
            when(morphPlanJpaRepository.save(any(MorphPlanEntity.class))).thenAnswer(invocation -> {
                MorphPlanEntity entity = invocation.getArgument(0);
                entity.setId(1L);
                return entity;
            });

            // Lineage save
            when(morphHistoryJpaRepository.save(any(MorphHistoryEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            MorphRequest request = MorphRequest.builder()
                    .sourceStrategyId(sourceId)
                    .targets(List.of(MorphTarget.builder()
                            .strategyType(StrategyType.BULL_PUT_SPREAD)
                            .retainedLegs(List.of("SELL_PE", "BUY_PE"))
                            .build()))
                    .copyEntryPrices(true)
                    .reason("Retain put spread only")
                    .build();

            MorphResult result = morphService.morph(request);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getNewStrategyIds()).hasSize(1);
            assertThat(result.getLegsClosedCount()).isZero();
            assertThat(result.getLegsReassignedCount()).isEqualTo(2);
            assertThat(result.getLegsOpenedCount()).isZero();

            // Verify source strategy was paused and closed
            verify(strategyEngine).pauseStrategy(sourceId);
            verify(strategyEngine).closeStrategy(sourceId);

            // Verify lineage was recorded
            ArgumentCaptor<MorphHistoryEntity> lineageCaptor = ArgumentCaptor.forClass(MorphHistoryEntity.class);
            verify(morphHistoryJpaRepository).save(lineageCaptor.capture());
            assertThat(lineageCaptor.getValue().getParentStrategyId()).isEqualTo(sourceId);

            // Verify event was published
            verify(applicationEventPublisher).publishEvent(any());

            // Verify decision was logged
            verify(decisionLogger).logMorph(eq(sourceId), eq(true), anyString(), any());
        }
    }

    // ========================
    // HELPERS
    // ========================

    private Position buildPosition(String id, String tradingSymbol, int quantity, Long instrumentToken) {
        return Position.builder()
                .id(id)
                .tradingSymbol(tradingSymbol)
                .quantity(quantity)
                .instrumentToken(instrumentToken)
                .averagePrice(BigDecimal.valueOf(100))
                .build();
    }
}
