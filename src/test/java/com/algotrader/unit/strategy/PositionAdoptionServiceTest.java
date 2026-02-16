package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.Instrument;
import com.algotrader.domain.model.Position;
import com.algotrader.entity.StrategyLegEntity;
import com.algotrader.exception.BusinessException;
import com.algotrader.exception.ResourceNotFoundException;
import com.algotrader.repository.jpa.StrategyLegJpaRepository;
import com.algotrader.repository.redis.PositionRedisRepository;
import com.algotrader.service.InstrumentService;
import com.algotrader.strategy.adoption.AdoptionResult;
import com.algotrader.strategy.adoption.PositionAdoptionService;
import com.algotrader.strategy.adoption.PositionAllocationService;
import com.algotrader.strategy.base.BaseStrategy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PositionAdoptionService} — covers adopt, detach, symbol parsing,
 * validation, and entry premium recalculation.
 */
@ExtendWith(MockitoExtension.class)
class PositionAdoptionServiceTest {

    @Mock
    private PositionRedisRepository positionRedisRepository;

    @Mock
    private StrategyLegJpaRepository strategyLegJpaRepository;

    @Mock
    private PositionAllocationService positionAllocationService;

    @Mock
    private StrategyEngine strategyEngine;

    @Mock
    private InstrumentService instrumentService;

    @Mock
    private BaseStrategy strategy;

    @InjectMocks
    private PositionAdoptionService positionAdoptionService;

    // ========================
    // ADOPT — SUCCESS
    // ========================

    @Nested
    @DisplayName("adoptPosition — success")
    class AdoptSuccess {

        @Test
        @DisplayName("creates StrategyLeg and returns result with entry premium")
        void successfulAdopt() {
            Position position = shortPosition("P1", "NIFTY2560519500CE", 1001L, -750, BigDecimal.valueOf(120));
            stubInstrument(1001L, BigDecimal.valueOf(19500), InstrumentType.CE);
            setupStrategyMock("STR-1", "NIFTY", StrategyType.STRADDLE);
            when(positionRedisRepository.findById("P1")).thenReturn(Optional.of(position));
            when(positionAllocationService.getUnmanagedQuantity("P1", -750)).thenReturn(750);
            when(strategyLegJpaRepository.findByStrategyId("STR-1")).thenReturn(List.of());
            when(strategy.getPositions()).thenReturn(new ArrayList<>());

            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, "P1", -300);

            assertThat(result.getStrategyId()).isEqualTo("STR-1");
            assertThat(result.getPositionId()).isEqualTo("P1");
            assertThat(result.getOperationType()).isEqualTo(AdoptionResult.OperationType.ADOPT);
            assertThat(result.getWarnings()).isEmpty();

            // Verify StrategyLeg was saved with correct fields
            ArgumentCaptor<StrategyLegEntity> captor = ArgumentCaptor.forClass(StrategyLegEntity.class);
            verify(strategyLegJpaRepository).save(captor.capture());
            StrategyLegEntity savedLeg = captor.getValue();
            assertThat(savedLeg.getStrategyId()).isEqualTo("STR-1");
            assertThat(savedLeg.getPositionId()).isEqualTo("P1");
            assertThat(savedLeg.getQuantity()).isEqualTo(-300);
            assertThat(savedLeg.getOptionType()).isEqualTo(InstrumentType.CE);
            assertThat(savedLeg.getStrike()).isEqualByComparingTo(BigDecimal.valueOf(19500));

            // Verify position added to strategy in-memory
            verify(strategy).addPosition(position);

            // Verify reverse index updated
            verify(strategyEngine).registerPositionLink("P1", "STR-1");
        }

        @Test
        @DisplayName("adopts long position with positive quantity")
        void adoptLongPosition() {
            Position position = longPosition("P1", "NIFTY2560519500CE", 1001L, 300, BigDecimal.valueOf(120));
            stubInstrument(1001L, BigDecimal.valueOf(19500), InstrumentType.CE);
            setupStrategyMock("STR-1", "NIFTY", StrategyType.BULL_CALL_SPREAD);
            when(positionRedisRepository.findById("P1")).thenReturn(Optional.of(position));
            when(positionAllocationService.getUnmanagedQuantity("P1", 300)).thenReturn(300);
            when(strategyLegJpaRepository.findByStrategyId("STR-1")).thenReturn(List.of());
            when(strategy.getPositions()).thenReturn(new ArrayList<>());

            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, "P1", 150);

            assertThat(result.getOperationType()).isEqualTo(AdoptionResult.OperationType.ADOPT);
            assertThat(result.getWarnings()).isEmpty();
            verify(strategyLegJpaRepository).save(any(StrategyLegEntity.class));
        }

        @Test
        @DisplayName("does not add position to in-memory list if already present")
        void doesNotDuplicateInMemory() {
            Position position = shortPosition("P1", "NIFTY2560519500CE", 1001L, -750, BigDecimal.valueOf(120));
            stubInstrument(1001L, BigDecimal.valueOf(19500), InstrumentType.CE);
            setupStrategyMock("STR-1", "NIFTY", StrategyType.STRADDLE);
            when(positionRedisRepository.findById("P1")).thenReturn(Optional.of(position));
            when(positionAllocationService.getUnmanagedQuantity("P1", -750)).thenReturn(750);
            when(strategyLegJpaRepository.findByStrategyId("STR-1")).thenReturn(List.of());
            // Position already in strategy's in-memory list
            when(strategy.getPositions()).thenReturn(new ArrayList<>(List.of(position)));

            positionAdoptionService.adoptPosition(strategy, "P1", -300);

            verify(strategy, never()).addPosition(any());
        }

        @Test
        @DisplayName("returns warnings for underlying mismatch but still succeeds")
        void warningForUnderlyingMismatch() {
            Position position = shortPosition("P1", "BANKNIFTY2560550000CE", 2001L, -750, BigDecimal.valueOf(200));
            stubInstrument(2001L, BigDecimal.valueOf(50000), InstrumentType.CE);
            setupStrategyMock("STR-1", "NIFTY", StrategyType.STRADDLE);
            when(positionRedisRepository.findById("P1")).thenReturn(Optional.of(position));
            when(positionAllocationService.getUnmanagedQuantity("P1", -750)).thenReturn(750);
            when(strategyLegJpaRepository.findByStrategyId("STR-1")).thenReturn(List.of());
            when(strategy.getPositions()).thenReturn(new ArrayList<>());

            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, "P1", -300);

            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.getWarnings()).anyMatch(w -> w.contains("BANKNIFTY") && w.contains("NIFTY"));
        }

        @Test
        @DisplayName("returns warning for CE position into PE-only strategy")
        void warningForOptionTypeMismatch() {
            Position position = shortPosition("P1", "NIFTY2560519500CE", 1001L, -750, BigDecimal.valueOf(120));
            stubInstrument(1001L, BigDecimal.valueOf(19500), InstrumentType.CE);
            setupStrategyMock("STR-1", "NIFTY", StrategyType.BULL_PUT_SPREAD);
            when(positionRedisRepository.findById("P1")).thenReturn(Optional.of(position));
            when(positionAllocationService.getUnmanagedQuantity("P1", -750)).thenReturn(750);
            when(strategyLegJpaRepository.findByStrategyId("STR-1")).thenReturn(List.of());
            when(strategy.getPositions()).thenReturn(new ArrayList<>());

            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, "P1", -300);

            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.getWarnings()).anyMatch(w -> w.contains("CE") && w.contains("PE"));
        }
    }

    // ========================
    // ADOPT — VALIDATION FAILURES
    // ========================

    @Nested
    @DisplayName("adoptPosition — validation failures")
    class AdoptValidation {

        @Test
        @DisplayName("throws when position not found")
        void positionNotFound() {
            when(positionRedisRepository.findById("P1")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionAdoptionService.adoptPosition(strategy, "P1", -300))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("throws when quantity is zero")
        void zeroQuantity() {
            Position position = shortPosition("P1", "NIFTY2560519500CE", 1001L, -750, BigDecimal.valueOf(120));
            when(positionRedisRepository.findById("P1")).thenReturn(Optional.of(position));

            assertThatThrownBy(() -> positionAdoptionService.adoptPosition(strategy, "P1", 0))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("zero");
        }

        @Test
        @DisplayName("throws when quantity sign does not match position sign")
        void signMismatch() {
            Position position = shortPosition("P1", "NIFTY2560519500CE", 1001L, -750, BigDecimal.valueOf(120));
            when(positionRedisRepository.findById("P1")).thenReturn(Optional.of(position));

            assertThatThrownBy(() -> positionAdoptionService.adoptPosition(strategy, "P1", 300))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("sign");
        }

        @Test
        @DisplayName("throws when quantity exceeds unmanaged remainder")
        void exceedsUnmanagedRemainder() {
            Position position = shortPosition("P1", "NIFTY2560519500CE", 1001L, -750, BigDecimal.valueOf(120));
            when(strategy.getId()).thenReturn("STR-1");
            when(positionRedisRepository.findById("P1")).thenReturn(Optional.of(position));
            // Only 150 unmanaged (600 already allocated)
            when(positionAllocationService.getUnmanagedQuantity("P1", -750)).thenReturn(150);

            assertThatThrownBy(() -> positionAdoptionService.adoptPosition(strategy, "P1", -300))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("exceeds unmanaged remainder");
        }

        @Test
        @DisplayName("throws when position already linked to strategy")
        void alreadyLinked() {
            Position position = shortPosition("P1", "NIFTY2560519500CE", 1001L, -750, BigDecimal.valueOf(120));
            when(strategy.getId()).thenReturn("STR-1");
            when(positionRedisRepository.findById("P1")).thenReturn(Optional.of(position));
            when(positionAllocationService.getUnmanagedQuantity("P1", -750)).thenReturn(750);

            // Existing leg already links P1 to STR-1
            StrategyLegEntity existingLeg = StrategyLegEntity.builder()
                    .id("L1")
                    .strategyId("STR-1")
                    .positionId("P1")
                    .quantity(-300)
                    .build();
            when(strategyLegJpaRepository.findByStrategyId("STR-1")).thenReturn(List.of(existingLeg));

            assertThatThrownBy(() -> positionAdoptionService.adoptPosition(strategy, "P1", -300))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already linked");
        }
    }

    // ========================
    // DETACH — SUCCESS
    // ========================

    @Nested
    @DisplayName("detachPosition — success")
    class DetachSuccess {

        @Test
        @DisplayName("clears positionId on leg and removes position from strategy")
        void successfulDetach() {
            when(strategy.getId()).thenReturn("STR-1");

            StrategyLegEntity linkedLeg = StrategyLegEntity.builder()
                    .id("L1")
                    .strategyId("STR-1")
                    .positionId("P1")
                    .quantity(-300)
                    .optionType(InstrumentType.CE)
                    .strike(BigDecimal.valueOf(19500))
                    .build();
            when(strategyLegJpaRepository.findByStrategyId("STR-1")).thenReturn(List.of(linkedLeg));
            when(strategy.getPositions()).thenReturn(new ArrayList<>());

            AdoptionResult result = positionAdoptionService.detachPosition(strategy, "P1");

            assertThat(result.getStrategyId()).isEqualTo("STR-1");
            assertThat(result.getPositionId()).isEqualTo("P1");
            assertThat(result.getOperationType()).isEqualTo(AdoptionResult.OperationType.DETACH);
            assertThat(result.getWarnings()).isEmpty();

            // Verify positionId cleared
            assertThat(linkedLeg.getPositionId()).isNull();
            verify(strategyLegJpaRepository).save(linkedLeg);

            // Verify position removed from in-memory list
            verify(strategy).removePosition("P1");

            // Verify reverse index updated
            verify(strategyEngine).unregisterPositionLink("P1", "STR-1");
        }

        @Test
        @DisplayName("does not remove position from in-memory if another leg still references it")
        void otherLegStillReferences() {
            when(strategy.getId()).thenReturn("STR-1");

            StrategyLegEntity leg1 = StrategyLegEntity.builder()
                    .id("L1")
                    .strategyId("STR-1")
                    .positionId("P1")
                    .quantity(-150)
                    .build();
            StrategyLegEntity leg2 = StrategyLegEntity.builder()
                    .id("L2")
                    .strategyId("STR-1")
                    .positionId("P1")
                    .quantity(-150)
                    .build();
            when(strategyLegJpaRepository.findByStrategyId("STR-1")).thenReturn(List.of(leg1, leg2));
            when(strategy.getPositions()).thenReturn(new ArrayList<>());

            // Detach leg1 — leg2 still references P1
            positionAdoptionService.detachPosition(strategy, "P1");

            // leg1's positionId should be cleared
            assertThat(leg1.getPositionId()).isNull();
            // But position should NOT be removed from in-memory (leg2 still references it)
            verify(strategy, never()).removePosition("P1");
            // Reverse index should NOT be updated either
            verify(strategyEngine, never()).unregisterPositionLink("P1", "STR-1");
        }
    }

    // ========================
    // DETACH — VALIDATION FAILURES
    // ========================

    @Nested
    @DisplayName("detachPosition — validation failures")
    class DetachValidation {

        @Test
        @DisplayName("throws when position not linked to strategy")
        void positionNotLinked() {
            when(strategy.getId()).thenReturn("STR-1");
            when(strategyLegJpaRepository.findByStrategyId("STR-1")).thenReturn(List.of());

            assertThatThrownBy(() -> positionAdoptionService.detachPosition(strategy, "P1"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("P1");
        }

        @Test
        @DisplayName("throws when strategy has legs but none linked to the given position")
        void noLegForPosition() {
            when(strategy.getId()).thenReturn("STR-1");

            StrategyLegEntity otherLeg = StrategyLegEntity.builder()
                    .id("L1")
                    .strategyId("STR-1")
                    .positionId("P2")
                    .quantity(-300)
                    .build();
            when(strategyLegJpaRepository.findByStrategyId("STR-1")).thenReturn(List.of(otherLeg));

            assertThatThrownBy(() -> positionAdoptionService.detachPosition(strategy, "P1"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ========================
    // ADOPTION RESULT
    // ========================

    @Nested
    @DisplayName("AdoptionResult")
    class AdoptionResultTests {

        @Test
        @DisplayName("hasWarnings returns false when warnings list is empty")
        void hasWarningsReturnsFalseWhenEmpty() {
            AdoptionResult result = AdoptionResult.builder()
                    .strategyId("STR-1")
                    .positionId("POS-1")
                    .operationType(AdoptionResult.OperationType.ADOPT)
                    .build();

            assertThat(result.hasWarnings()).isFalse();
        }

        @Test
        @DisplayName("hasWarnings returns true when warnings present")
        void hasWarningsReturnsTrueWhenPresent() {
            AdoptionResult result = AdoptionResult.builder()
                    .strategyId("STR-1")
                    .positionId("POS-1")
                    .operationType(AdoptionResult.OperationType.ADOPT)
                    .warnings(List.of("Some warning"))
                    .build();

            assertThat(result.hasWarnings()).isTrue();
        }
    }

    // ========================
    // HELPERS
    // ========================

    private void setupStrategyMock(String id, String underlying, StrategyType type) {
        when(strategy.getId()).thenReturn(id);
        when(strategy.getUnderlying()).thenReturn(underlying);
        when(strategy.getType()).thenReturn(type);
    }

    private static Position shortPosition(
            String id, String tradingSymbol, long instrumentToken, int quantity, BigDecimal avgPrice) {
        return Position.builder()
                .id(id)
                .tradingSymbol(tradingSymbol)
                .instrumentToken(instrumentToken)
                .quantity(quantity)
                .averagePrice(avgPrice)
                .build();
    }

    private static Position longPosition(
            String id, String tradingSymbol, long instrumentToken, int quantity, BigDecimal avgPrice) {
        return Position.builder()
                .id(id)
                .tradingSymbol(tradingSymbol)
                .instrumentToken(instrumentToken)
                .quantity(quantity)
                .averagePrice(avgPrice)
                .build();
    }

    /** Stubs instrumentService.findByToken to return an instrument with given strike and type. */
    private void stubInstrument(long token, BigDecimal strike, InstrumentType type) {
        Instrument instrument =
                Instrument.builder().token(token).strike(strike).type(type).build();
        when(instrumentService.findByToken(token)).thenReturn(Optional.of(instrument));
    }
}
