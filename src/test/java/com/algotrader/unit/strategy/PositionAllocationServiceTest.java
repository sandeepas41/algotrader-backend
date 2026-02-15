package com.algotrader.unit.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.algotrader.entity.StrategyLegEntity;
import com.algotrader.repository.jpa.StrategyLegJpaRepository;
import com.algotrader.strategy.adoption.PositionAllocationService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link PositionAllocationService} — verifies allocation quantity computation
 * across fully unmanaged, partially managed, fully managed, and over-allocated scenarios.
 */
@ExtendWith(MockitoExtension.class)
class PositionAllocationServiceTest {

    @Mock
    private StrategyLegJpaRepository strategyLegJpaRepository;

    @InjectMocks
    private PositionAllocationService positionAllocationService;

    // ========================
    // getAllocatedQuantity
    // ========================

    @Nested
    @DisplayName("getAllocatedQuantity")
    class GetAllocatedQuantity {

        @Test
        @DisplayName("returns 0 when no legs reference the position")
        void fullyUnmanaged() {
            when(strategyLegJpaRepository.findByPositionId("P1")).thenReturn(List.of());
            assertThat(positionAllocationService.getAllocatedQuantity("P1")).isZero();
        }

        @Test
        @DisplayName("sums leg quantities for a single strategy")
        void singleStrategy() {
            when(strategyLegJpaRepository.findByPositionId("P1"))
                    .thenReturn(List.of(legEntity("L1", "S1", "P1", -300)));

            assertThat(positionAllocationService.getAllocatedQuantity("P1")).isEqualTo(-300);
        }

        @Test
        @DisplayName("sums leg quantities across multiple strategies")
        void multipleStrategies() {
            when(strategyLegJpaRepository.findByPositionId("P1"))
                    .thenReturn(List.of(legEntity("L1", "S1", "P1", -300), legEntity("L2", "S2", "P1", -450)));

            assertThat(positionAllocationService.getAllocatedQuantity("P1")).isEqualTo(-750);
        }
    }

    // ========================
    // getUnmanagedQuantity
    // ========================

    @Nested
    @DisplayName("getUnmanagedQuantity")
    class GetUnmanagedQuantity {

        @Test
        @DisplayName("fully unmanaged — returns full position quantity")
        void fullyUnmanaged() {
            when(strategyLegJpaRepository.findByPositionId("P1")).thenReturn(List.of());
            assertThat(positionAllocationService.getUnmanagedQuantity("P1", -750))
                    .isEqualTo(750);
        }

        @Test
        @DisplayName("partially managed — returns remainder")
        void partiallyManaged() {
            when(strategyLegJpaRepository.findByPositionId("P1"))
                    .thenReturn(List.of(legEntity("L1", "S1", "P1", -300)));

            assertThat(positionAllocationService.getUnmanagedQuantity("P1", -750))
                    .isEqualTo(450);
        }

        @Test
        @DisplayName("fully managed — returns 0")
        void fullyManaged() {
            when(strategyLegJpaRepository.findByPositionId("P1"))
                    .thenReturn(List.of(legEntity("L1", "S1", "P1", -300), legEntity("L2", "S2", "P1", -450)));

            assertThat(positionAllocationService.getUnmanagedQuantity("P1", -750))
                    .isZero();
        }

        @Test
        @DisplayName("over-allocated — returns negative value")
        void overAllocated() {
            // Position shrank from -750 to -500 but allocations still total 750
            when(strategyLegJpaRepository.findByPositionId("P1"))
                    .thenReturn(List.of(legEntity("L1", "S1", "P1", -300), legEntity("L2", "S2", "P1", -450)));

            assertThat(positionAllocationService.getUnmanagedQuantity("P1", -500))
                    .isEqualTo(-250);
        }

        @Test
        @DisplayName("long position — uses absolute values")
        void longPosition() {
            when(strategyLegJpaRepository.findByPositionId("P1")).thenReturn(List.of(legEntity("L1", "S1", "P1", 150)));

            assertThat(positionAllocationService.getUnmanagedQuantity("P1", 300))
                    .isEqualTo(150);
        }
    }

    // ========================
    // getAllocationMap (bulk)
    // ========================

    @Nested
    @DisplayName("getAllocationMap")
    class GetAllocationMap {

        @Test
        @DisplayName("returns empty map for empty input")
        void emptyInput() {
            assertThat(positionAllocationService.getAllocationMap(Set.of())).isEmpty();
        }

        @Test
        @DisplayName("returns allocations grouped by positionId")
        void groupedByPosition() {
            when(strategyLegJpaRepository.findByPositionIdIn(Set.of("P1", "P2")))
                    .thenReturn(List.of(
                            legEntity("L1", "S1", "P1", -300),
                            legEntity("L2", "S2", "P1", -150),
                            legEntity("L3", "S1", "P2", -75)));

            Map<String, Integer> result = positionAllocationService.getAllocationMap(Set.of("P1", "P2"));

            assertThat(result).hasSize(2);
            assertThat(result.get("P1")).isEqualTo(-450);
            assertThat(result.get("P2")).isEqualTo(-75);
        }

        @Test
        @DisplayName("positions without allocations are absent from the map")
        void noAllocationsAbsent() {
            when(strategyLegJpaRepository.findByPositionIdIn(Set.of("P1", "P2")))
                    .thenReturn(List.of(legEntity("L1", "S1", "P1", -300)));

            Map<String, Integer> result = positionAllocationService.getAllocationMap(Set.of("P1", "P2"));

            assertThat(result).containsOnlyKeys("P1");
            assertThat(result.get("P1")).isEqualTo(-300);
        }
    }

    // ========================
    // getAllAllocations
    // ========================

    @Nested
    @DisplayName("getAllAllocations")
    class GetAllAllocations {

        @Test
        @DisplayName("returns all allocations across all positions")
        void allAllocations() {
            when(strategyLegJpaRepository.findByPositionIdIsNotNull())
                    .thenReturn(List.of(
                            legEntity("L1", "S1", "P1", -300),
                            legEntity("L2", "S2", "P1", -450),
                            legEntity("L3", "S1", "P3", -75)));

            Map<String, Integer> result = positionAllocationService.getAllAllocations();

            assertThat(result).hasSize(2);
            assertThat(result.get("P1")).isEqualTo(-750);
            assertThat(result.get("P3")).isEqualTo(-75);
        }

        @Test
        @DisplayName("returns empty map when no legs are linked")
        void noLinkedLegs() {
            when(strategyLegJpaRepository.findByPositionIdIsNotNull()).thenReturn(List.of());
            assertThat(positionAllocationService.getAllAllocations()).isEmpty();
        }
    }

    // ========================
    // Helpers
    // ========================

    private static StrategyLegEntity legEntity(String id, String strategyId, String positionId, int quantity) {
        return StrategyLegEntity.builder()
                .id(id)
                .strategyId(strategyId)
                .positionId(positionId)
                .quantity(quantity)
                .build();
    }
}
