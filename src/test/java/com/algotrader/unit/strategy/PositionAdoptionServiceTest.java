package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.domain.model.Position;
import com.algotrader.exception.BusinessException;
import com.algotrader.exception.ResourceNotFoundException;
import com.algotrader.repository.redis.PositionRedisRepository;
import com.algotrader.strategy.adoption.AdoptionResult;
import com.algotrader.strategy.adoption.PositionAdoptionService;
import com.algotrader.strategy.base.BaseStrategy;
import com.algotrader.strategy.impl.StraddleConfig;
import com.algotrader.strategy.impl.StraddleStrategy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for PositionAdoptionService covering adopt, detach, validation,
 * entry premium recalculation, quantity mismatch warnings, and orphan detection.
 */
@ExtendWith(MockitoExtension.class)
class PositionAdoptionServiceTest {

    private static final String STRATEGY_ID = "STR-ADOPT-01";
    private static final String POSITION_ID = "POS-001";

    @Mock
    private PositionRedisRepository positionRedisRepository;

    private PositionAdoptionService positionAdoptionService;

    @BeforeEach
    void setUp() {
        positionAdoptionService = new PositionAdoptionService(positionRedisRepository);
    }

    // ========================
    // ADOPT -- HAPPY PATH
    // ========================

    @Nested
    @DisplayName("Adopt Position -- Happy Path")
    class AdoptHappyPath {

        @Test
        @DisplayName("Adopts unassigned position into strategy successfully")
        void adoptsUnassignedPosition() {
            BaseStrategy strategy = createStraddleStrategy();
            Position position = createPosition(POSITION_ID, null, "NIFTY22000CE", 75, BigDecimal.valueOf(150));
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, POSITION_ID);

            assertThat(result.getStrategyId()).isEqualTo(STRATEGY_ID);
            assertThat(result.getPositionId()).isEqualTo(POSITION_ID);
            assertThat(result.getOperationType()).isEqualTo(AdoptionResult.OperationType.ADOPT);
            assertThat(result.getRecalculatedEntryPremium()).isNotNull();
            verify(positionRedisRepository).save(position);
            assertThat(position.getStrategyId()).isEqualTo(STRATEGY_ID);
        }

        @Test
        @DisplayName("Position is added to strategy's in-memory list")
        void positionAddedToStrategyList() {
            BaseStrategy strategy = createStraddleStrategy();
            Position position = createPosition(POSITION_ID, null, "NIFTY22000CE", 75, BigDecimal.valueOf(150));
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            positionAdoptionService.adoptPosition(strategy, POSITION_ID);

            assertThat(strategy.getPositions()).hasSize(1);
            assertThat(strategy.getPositions().get(0).getId()).isEqualTo(POSITION_ID);
        }

        @Test
        @DisplayName("Entry premium is recalculated after adoption")
        void entryPremiumRecalculated() {
            BaseStrategy strategy = createStraddleStrategy();
            Position position = createPosition(POSITION_ID, null, "NIFTY22000CE", 75, BigDecimal.valueOf(200));
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, POSITION_ID);

            // Single position: entryPremium = averagePrice * |qty| / |qty| = averagePrice
            assertThat(result.getRecalculatedEntryPremium()).isEqualByComparingTo(BigDecimal.valueOf(200));
            assertThat(strategy.getEntryPremium()).isEqualByComparingTo(BigDecimal.valueOf(200));
        }

        @Test
        @DisplayName("No warnings for compatible position")
        void noWarningsForCompatiblePosition() {
            BaseStrategy strategy = createStraddleStrategy();
            Position position = createPosition(POSITION_ID, null, "NIFTY22000CE", -75, BigDecimal.valueOf(150));
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, POSITION_ID);

            assertThat(result.hasWarnings()).isFalse();
        }
    }

    // ========================
    // ADOPT -- VALIDATION ERRORS
    // ========================

    @Nested
    @DisplayName("Adopt Position -- Validation Errors")
    class AdoptValidationErrors {

        @Test
        @DisplayName("Throws when position doesn't exist")
        void throwsWhenPositionNotFound() {
            BaseStrategy strategy = createStraddleStrategy();
            when(positionRedisRepository.findById("POS-MISSING")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionAdoptionService.adoptPosition(strategy, "POS-MISSING"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Position");
        }

        @Test
        @DisplayName("Throws when position assigned to different strategy")
        void throwsWhenAssignedToDifferentStrategy() {
            BaseStrategy strategy = createStraddleStrategy();
            Position position =
                    createPosition(POSITION_ID, "STR-OTHER-99", "NIFTY22000CE", 75, BigDecimal.valueOf(150));
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            assertThatThrownBy(() -> positionAdoptionService.adoptPosition(strategy, POSITION_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already belongs to strategy");
        }

        @Test
        @DisplayName("Throws when position already assigned to same strategy")
        void throwsWhenAlreadyAssignedToSameStrategy() {
            BaseStrategy strategy = createStraddleStrategy();
            Position position = createPosition(POSITION_ID, STRATEGY_ID, "NIFTY22000CE", 75, BigDecimal.valueOf(150));
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            assertThatThrownBy(() -> positionAdoptionService.adoptPosition(strategy, POSITION_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already assigned to strategy");
        }

        @Test
        @DisplayName("Does not save when validation fails")
        void doesNotSaveOnValidationFailure() {
            BaseStrategy strategy = createStraddleStrategy();
            Position position =
                    createPosition(POSITION_ID, "STR-OTHER-99", "NIFTY22000CE", 75, BigDecimal.valueOf(150));
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            try {
                positionAdoptionService.adoptPosition(strategy, POSITION_ID);
            } catch (Exception ignored) {
            }

            verify(positionRedisRepository, never()).save(any());
        }
    }

    // ========================
    // ADOPT -- WARNINGS
    // ========================

    @Nested
    @DisplayName("Adopt Position -- Warnings")
    class AdoptWarnings {

        @Test
        @DisplayName("Warns when underlying doesn't match")
        void warnsOnUnderlyingMismatch() {
            BaseStrategy strategy = createStraddleStrategy(); // underlying = "NIFTY"
            Position position = createPosition(POSITION_ID, null, "BANKNIFTY45000CE", 30, BigDecimal.valueOf(300));
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, POSITION_ID);

            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.getWarnings()).anyMatch(w -> w.contains("may not match strategy underlying"));
        }

        @Test
        @DisplayName("Warns when PE position adopted into CE-only strategy")
        void warnsOnPEIntoCEOnlyStrategy() {
            BaseStrategy strategy = createBullCallSpreadStrategy();
            Position position = createPosition(POSITION_ID, null, "NIFTY22000PE", 75, BigDecimal.valueOf(100));
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, POSITION_ID);

            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.getWarnings()).anyMatch(w -> w.contains("PE position adopted into BULL_CALL_SPREAD"));
        }

        @Test
        @DisplayName("No option type warning for straddle (uses both CE and PE)")
        void noWarningForStraddleBothSides() {
            BaseStrategy strategy = createStraddleStrategy();
            Position position = createPosition(POSITION_ID, null, "NIFTY22000PE", -75, BigDecimal.valueOf(150));
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, POSITION_ID);

            assertThat(result.getWarnings())
                    .noneMatch(w -> w.contains("PE position adopted into") || w.contains("CE position adopted into"));
        }

        @Test
        @DisplayName("Warns when position quantity differs from existing positions")
        void warnsOnQuantityMismatch() {
            BaseStrategy strategy = createStraddleStrategy();

            // Add an existing position with qty=75
            Position existing =
                    createPosition("POS-EXISTING", STRATEGY_ID, "NIFTY22000CE", -75, BigDecimal.valueOf(150));
            strategy.addPosition(existing);

            // Adopt a position with qty=50 (mismatch)
            Position newPosition = createPosition(POSITION_ID, null, "NIFTY22000PE", -50, BigDecimal.valueOf(140));
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(newPosition));

            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, POSITION_ID);

            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.getWarnings()).anyMatch(w -> w.contains("Position quantity (50) differs from"));
        }

        @Test
        @DisplayName("No quantity warning when no existing positions")
        void noQuantityWarningWhenNoExistingPositions() {
            BaseStrategy strategy = createStraddleStrategy();
            Position position = createPosition(POSITION_ID, null, "NIFTY22000CE", -75, BigDecimal.valueOf(150));
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, POSITION_ID);

            assertThat(result.getWarnings()).noneMatch(w -> w.contains("Position quantity"));
        }
    }

    // ========================
    // DETACH
    // ========================

    @Nested
    @DisplayName("Detach Position")
    class DetachPosition {

        @Test
        @DisplayName("Detaches position successfully")
        void detachesPositionSuccessfully() {
            BaseStrategy strategy = createStraddleStrategy();
            Position position = createPosition(POSITION_ID, STRATEGY_ID, "NIFTY22000CE", -75, BigDecimal.valueOf(150));
            strategy.addPosition(position);
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            AdoptionResult result = positionAdoptionService.detachPosition(strategy, POSITION_ID);

            assertThat(result.getStrategyId()).isEqualTo(STRATEGY_ID);
            assertThat(result.getPositionId()).isEqualTo(POSITION_ID);
            assertThat(result.getOperationType()).isEqualTo(AdoptionResult.OperationType.DETACH);
            assertThat(position.getStrategyId()).isNull();
            verify(positionRedisRepository).save(position);
        }

        @Test
        @DisplayName("Position is removed from strategy's in-memory list")
        void positionRemovedFromStrategyList() {
            BaseStrategy strategy = createStraddleStrategy();
            Position position = createPosition(POSITION_ID, STRATEGY_ID, "NIFTY22000CE", -75, BigDecimal.valueOf(150));
            strategy.addPosition(position);
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            positionAdoptionService.detachPosition(strategy, POSITION_ID);

            assertThat(strategy.getPositions()).isEmpty();
        }

        @Test
        @DisplayName("Entry premium recalculated after detach (null when no positions remain)")
        void entryPremiumNullAfterLastDetach() {
            BaseStrategy strategy = createStraddleStrategy();
            strategy.setEntryPremium(BigDecimal.valueOf(150));
            Position position = createPosition(POSITION_ID, STRATEGY_ID, "NIFTY22000CE", -75, BigDecimal.valueOf(150));
            strategy.addPosition(position);
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            AdoptionResult result = positionAdoptionService.detachPosition(strategy, POSITION_ID);

            assertThat(result.getRecalculatedEntryPremium()).isNull();
            assertThat(strategy.getEntryPremium()).isNull();
        }

        @Test
        @DisplayName("Entry premium recalculated from remaining positions after detach")
        void entryPremiumRecalculatedFromRemaining() {
            BaseStrategy strategy = createStraddleStrategy();
            Position pos1 = createPosition("POS-1", STRATEGY_ID, "NIFTY22000CE", -75, BigDecimal.valueOf(200));
            Position pos2 = createPosition("POS-2", STRATEGY_ID, "NIFTY22000PE", -75, BigDecimal.valueOf(100));
            strategy.addPosition(pos1);
            strategy.addPosition(pos2);
            when(positionRedisRepository.findById("POS-1")).thenReturn(Optional.of(pos1));

            // Detach pos1 -> remaining is pos2 with avgPrice=100
            positionAdoptionService.detachPosition(strategy, "POS-1");

            assertThat(strategy.getEntryPremium()).isEqualByComparingTo(BigDecimal.valueOf(100));
        }

        @Test
        @DisplayName("Throws when position doesn't belong to this strategy")
        void throwsWhenPositionNotInStrategy() {
            BaseStrategy strategy = createStraddleStrategy();
            Position position = createPosition(POSITION_ID, "STR-OTHER", "NIFTY22000CE", -75, BigDecimal.valueOf(150));
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            assertThatThrownBy(() -> positionAdoptionService.detachPosition(strategy, POSITION_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("does not belong to strategy");
        }

        @Test
        @DisplayName("Throws when position doesn't exist")
        void throwsWhenPositionNotFound() {
            BaseStrategy strategy = createStraddleStrategy();
            when(positionRedisRepository.findById("POS-GONE")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionAdoptionService.detachPosition(strategy, "POS-GONE"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Detach result has no warnings")
        void detachResultHasNoWarnings() {
            BaseStrategy strategy = createStraddleStrategy();
            Position position = createPosition(POSITION_ID, STRATEGY_ID, "NIFTY22000CE", -75, BigDecimal.valueOf(150));
            strategy.addPosition(position);
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            AdoptionResult result = positionAdoptionService.detachPosition(strategy, POSITION_ID);

            assertThat(result.hasWarnings()).isFalse();
        }
    }

    // ========================
    // ENTRY PREMIUM RECALCULATION (tested through adopt/detach)
    // ========================

    @Nested
    @DisplayName("Entry Premium Recalculation")
    class EntryPremiumRecalculation {

        @Test
        @DisplayName("Single position adoption: entryPremium equals averagePrice")
        void singlePositionPremium() {
            BaseStrategy strategy = createStraddleStrategy();
            Position position = createPosition(POSITION_ID, null, "NIFTY22000CE", -75, BigDecimal.valueOf(180));
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, POSITION_ID);

            assertThat(result.getRecalculatedEntryPremium()).isEqualByComparingTo(BigDecimal.valueOf(180));
            assertThat(strategy.getEntryPremium()).isEqualByComparingTo(BigDecimal.valueOf(180));
        }

        @Test
        @DisplayName("Two positions adopted: weighted average by quantity")
        void twoPositionsWeightedAverage() {
            BaseStrategy strategy = createStraddleStrategy();
            Position pos1 = createPosition("P1", null, "NIFTY22000CE", -75, BigDecimal.valueOf(200));
            Position pos2 = createPosition("P2", null, "NIFTY22000PE", -75, BigDecimal.valueOf(100));
            when(positionRedisRepository.findById("P1")).thenReturn(Optional.of(pos1));
            when(positionRedisRepository.findById("P2")).thenReturn(Optional.of(pos2));

            positionAdoptionService.adoptPosition(strategy, "P1");
            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, "P2");

            // (200*75 + 100*75) / (75+75) = 22500 / 150 = 150
            assertThat(result.getRecalculatedEntryPremium()).isEqualByComparingTo(BigDecimal.valueOf(150));
        }

        @Test
        @DisplayName("Different quantities: correctly weighted")
        void differentQuantitiesWeighted() {
            BaseStrategy strategy = createStraddleStrategy();
            Position pos1 = createPosition("P1", null, "NIFTY22000CE", -150, BigDecimal.valueOf(200));
            Position pos2 = createPosition("P2", null, "NIFTY22000PE", -75, BigDecimal.valueOf(100));
            when(positionRedisRepository.findById("P1")).thenReturn(Optional.of(pos1));
            when(positionRedisRepository.findById("P2")).thenReturn(Optional.of(pos2));

            positionAdoptionService.adoptPosition(strategy, "P1");
            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, "P2");

            // (200*150 + 100*75) / (150+75) = 37500 / 225 = 166.67
            assertThat(result.getRecalculatedEntryPremium().doubleValue())
                    .isCloseTo(166.67, org.assertj.core.data.Offset.offset(0.01));
        }

        @Test
        @DisplayName("Detach last position: entryPremium becomes null")
        void detachLastPositionNullsPremium() {
            BaseStrategy strategy = createStraddleStrategy();
            Position position = createPosition(POSITION_ID, null, "NIFTY22000CE", -75, BigDecimal.valueOf(180));
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));
            positionAdoptionService.adoptPosition(strategy, POSITION_ID);

            // Re-mock for detach (position now has strategyId set)
            when(positionRedisRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));
            AdoptionResult detachResult = positionAdoptionService.detachPosition(strategy, POSITION_ID);

            assertThat(detachResult.getRecalculatedEntryPremium()).isNull();
            assertThat(strategy.getEntryPremium()).isNull();
        }

        @Test
        @DisplayName("Position with null averagePrice contributes zero to weighted sum")
        void nullAveragePriceHandled() {
            BaseStrategy strategy = createStraddleStrategy();
            Position pos1 = createPosition("P1", null, "NIFTY22000CE", -75, BigDecimal.valueOf(200));
            Position pos2 = createPosition("P2", null, "NIFTY22000PE", -75, null);
            when(positionRedisRepository.findById("P1")).thenReturn(Optional.of(pos1));
            when(positionRedisRepository.findById("P2")).thenReturn(Optional.of(pos2));

            positionAdoptionService.adoptPosition(strategy, "P1");
            AdoptionResult result = positionAdoptionService.adoptPosition(strategy, "P2");

            // Only pos1 contributes: (200*75) / (75+75) = 15000 / 150 = 100
            assertThat(result.getRecalculatedEntryPremium()).isEqualByComparingTo(BigDecimal.valueOf(100));
        }
    }

    // ========================
    // ORPHAN DETECTION
    // ========================

    @Nested
    @DisplayName("Orphan Detection")
    class OrphanDetection {

        @Test
        @DisplayName("Finds positions with no strategy assignment")
        void findsOrphanPositions() {
            Position orphan1 = createPosition("POS-O1", null, "NIFTY22000CE", 75, BigDecimal.valueOf(150));
            Position orphan2 = createPosition("POS-O2", null, "NIFTY22000PE", -75, BigDecimal.valueOf(140));
            Position assigned = createPosition("POS-A1", "STR-X", "NIFTY22000CE", -75, BigDecimal.valueOf(160));
            when(positionRedisRepository.findAll()).thenReturn(List.of(orphan1, orphan2, assigned));

            List<Position> orphans = positionAdoptionService.findOrphanPositions();

            assertThat(orphans).hasSize(2);
            assertThat(orphans).extracting(Position::getId).containsExactlyInAnyOrder("POS-O1", "POS-O2");
        }

        @Test
        @DisplayName("Returns empty list when no orphans")
        void returnsEmptyWhenNoOrphans() {
            Position assigned = createPosition("POS-A1", "STR-X", "NIFTY22000CE", -75, BigDecimal.valueOf(160));
            when(positionRedisRepository.findAll()).thenReturn(List.of(assigned));

            List<Position> orphans = positionAdoptionService.findOrphanPositions();

            assertThat(orphans).isEmpty();
        }

        @Test
        @DisplayName("Returns empty list when no positions at all")
        void returnsEmptyWhenNoPositions() {
            when(positionRedisRepository.findAll()).thenReturn(List.of());

            List<Position> orphans = positionAdoptionService.findOrphanPositions();

            assertThat(orphans).isEmpty();
        }
    }

    // ========================
    // STALE ASSIGNMENT DETECTION
    // ========================

    @Nested
    @DisplayName("Stale Assignment Detection")
    class StaleAssignmentDetection {

        @Test
        @DisplayName("Finds positions assigned to non-active strategies")
        void findsStaleAssignments() {
            Position active = createPosition("POS-A", "STR-ACTIVE", "NIFTY22000CE", -75, BigDecimal.valueOf(150));
            Position stale = createPosition("POS-S", "STR-DEAD", "NIFTY22000PE", -75, BigDecimal.valueOf(140));
            Position orphan = createPosition("POS-O", null, "NIFTY22000CE", 75, BigDecimal.valueOf(160));
            when(positionRedisRepository.findAll()).thenReturn(List.of(active, stale, orphan));

            Set<String> activeStrategyIds = Set.of("STR-ACTIVE");
            List<Position> stalePositions = positionAdoptionService.findStaleAssignments(activeStrategyIds);

            assertThat(stalePositions).hasSize(1);
            assertThat(stalePositions.get(0).getId()).isEqualTo("POS-S");
        }

        @Test
        @DisplayName("Returns empty when all assignments are valid")
        void returnsEmptyWhenAllValid() {
            Position active = createPosition("POS-A", "STR-ACTIVE", "NIFTY22000CE", -75, BigDecimal.valueOf(150));
            when(positionRedisRepository.findAll()).thenReturn(List.of(active));

            List<Position> stale = positionAdoptionService.findStaleAssignments(Set.of("STR-ACTIVE"));

            assertThat(stale).isEmpty();
        }

        @Test
        @DisplayName("Orphan positions (null strategyId) are excluded from stale results")
        void orphansExcludedFromStale() {
            Position orphan = createPosition("POS-O", null, "NIFTY22000CE", 75, BigDecimal.valueOf(160));
            when(positionRedisRepository.findAll()).thenReturn(List.of(orphan));

            List<Position> stale = positionAdoptionService.findStaleAssignments(Set.of("STR-ACTIVE"));

            assertThat(stale).isEmpty();
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

    private Position createPosition(
            String id, String strategyId, String tradingSymbol, int quantity, BigDecimal averagePrice) {
        return Position.builder()
                .id(id)
                .strategyId(strategyId)
                .tradingSymbol(tradingSymbol)
                .quantity(quantity)
                .averagePrice(averagePrice)
                .unrealizedPnl(BigDecimal.ZERO)
                .realizedPnl(BigDecimal.ZERO)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private BaseStrategy createStraddleStrategy() {
        StraddleConfig config = StraddleConfig.builder()
                .underlying("NIFTY")
                .lots(1)
                .strikeInterval(BigDecimal.valueOf(50))
                .minIV(BigDecimal.valueOf(12))
                .shiftDeltaThreshold(BigDecimal.valueOf(0.35))
                .targetPercent(BigDecimal.valueOf(0.5))
                .stopLossMultiplier(BigDecimal.valueOf(1.5))
                .minDaysToExpiry(1)
                .build();
        return new TestableStraddleStrategy(STRATEGY_ID, "NIFTY-Straddle", config);
    }

    private BaseStrategy createBullCallSpreadStrategy() {
        // Use BullCallSpreadStrategy as a different strategy type
        // to test cross-strategy position adoption
        com.algotrader.strategy.impl.BullCallSpreadConfig bcsConfig =
                com.algotrader.strategy.impl.BullCallSpreadConfig.builder()
                        .underlying("NIFTY")
                        .lots(1)
                        .strikeInterval(BigDecimal.valueOf(50))
                        .buyOffset(BigDecimal.ZERO)
                        .sellOffset(BigDecimal.valueOf(200))
                        .targetPercent(BigDecimal.valueOf(0.6))
                        .stopLossMultiplier(BigDecimal.valueOf(2.0))
                        .minDaysToExpiry(1)
                        .build();
        return new TestableBullCallSpreadStrategy("STR-BCS-01", "NIFTY-BCS", bcsConfig);
    }

    /**
     * Testable subclass that allows setting the strategy ID for predictable test assertions.
     */
    static class TestableStraddleStrategy extends StraddleStrategy {

        TestableStraddleStrategy(String id, String name, StraddleConfig config) {
            super(id, name, config);
        }
    }

    /**
     * Testable subclass for BullCallSpreadStrategy.
     */
    static class TestableBullCallSpreadStrategy extends com.algotrader.strategy.impl.BullCallSpreadStrategy {

        TestableBullCallSpreadStrategy(
                String id, String name, com.algotrader.strategy.impl.BullCallSpreadConfig config) {
            super(id, name, config);
        }
    }
}
