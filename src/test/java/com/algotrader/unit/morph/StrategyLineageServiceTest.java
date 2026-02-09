package com.algotrader.unit.morph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.algotrader.domain.enums.StrategyType;
import com.algotrader.domain.model.StrategyLineageTree;
import com.algotrader.entity.MorphHistoryEntity;
import com.algotrader.morph.StrategyLineageService;
import com.algotrader.repository.jpa.MorphHistoryJpaRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for StrategyLineageService: tree construction, ancestor/descendant
 * traversal, and cumulative P&L calculation across morph chains.
 */
@ExtendWith(MockitoExtension.class)
class StrategyLineageServiceTest {

    @Mock
    private MorphHistoryJpaRepository morphHistoryJpaRepository;

    private StrategyLineageService strategyLineageService;

    @BeforeEach
    void setUp() {
        strategyLineageService = new StrategyLineageService(morphHistoryJpaRepository);
    }

    // ========================
    // LINEAGE TREE
    // ========================

    @Nested
    @DisplayName("getLineageTree()")
    class LineageTree {

        @Test
        @DisplayName("should build tree with no ancestors or descendants")
        void emptyTree() {
            when(morphHistoryJpaRepository.findByChildStrategyId("S1")).thenReturn(Optional.empty());
            when(morphHistoryJpaRepository.findByParentStrategyId("S1")).thenReturn(List.of());

            StrategyLineageTree tree = strategyLineageService.getLineageTree("S1");

            assertThat(tree.getStrategyId()).isEqualTo("S1");
            assertThat(tree.getAncestors()).isEmpty();
            assertThat(tree.getDescendants()).isEmpty();
        }

        @Test
        @DisplayName("should find single ancestor (parent)")
        void singleAncestor() {
            MorphHistoryEntity parentLink = buildEntity(
                    1L, "PARENT", "CHILD", StrategyType.STRADDLE, StrategyType.IRON_CONDOR, new BigDecimal("-3000"));

            when(morphHistoryJpaRepository.findByChildStrategyId("CHILD")).thenReturn(Optional.of(parentLink));
            when(morphHistoryJpaRepository.findByChildStrategyId("PARENT")).thenReturn(Optional.empty());
            when(morphHistoryJpaRepository.findByParentStrategyId("CHILD")).thenReturn(List.of());

            StrategyLineageTree tree = strategyLineageService.getLineageTree("CHILD");

            assertThat(tree.getAncestors()).hasSize(1);
            assertThat(tree.getAncestors().get(0).getParentStrategyId()).isEqualTo("PARENT");
        }

        @Test
        @DisplayName("should find chain of ancestors (grandparent -> parent -> child)")
        void ancestorChain() {
            MorphHistoryEntity parentLink = buildEntity(
                    1L,
                    "PARENT",
                    "CHILD",
                    StrategyType.IRON_CONDOR,
                    StrategyType.BULL_PUT_SPREAD,
                    new BigDecimal("5000"));
            MorphHistoryEntity grandparentLink = buildEntity(
                    2L,
                    "GRANDPARENT",
                    "PARENT",
                    StrategyType.STRADDLE,
                    StrategyType.IRON_CONDOR,
                    new BigDecimal("-2000"));

            when(morphHistoryJpaRepository.findByChildStrategyId("CHILD")).thenReturn(Optional.of(parentLink));
            when(morphHistoryJpaRepository.findByChildStrategyId("PARENT")).thenReturn(Optional.of(grandparentLink));
            when(morphHistoryJpaRepository.findByChildStrategyId("GRANDPARENT")).thenReturn(Optional.empty());
            when(morphHistoryJpaRepository.findByParentStrategyId("CHILD")).thenReturn(List.of());

            StrategyLineageTree tree = strategyLineageService.getLineageTree("CHILD");

            assertThat(tree.getAncestors()).hasSize(2);
            assertThat(tree.getAncestors().get(0).getParentStrategyId()).isEqualTo("PARENT");
            assertThat(tree.getAncestors().get(1).getParentStrategyId()).isEqualTo("GRANDPARENT");
        }

        @Test
        @DisplayName("should find descendants (one-to-many morph)")
        void descendants() {
            MorphHistoryEntity child1 = buildEntity(
                    1L,
                    "PARENT",
                    "CHILD1",
                    StrategyType.IRON_CONDOR,
                    StrategyType.BULL_PUT_SPREAD,
                    new BigDecimal("3000"));
            MorphHistoryEntity child2 = buildEntity(
                    2L, "PARENT", "CHILD2", StrategyType.IRON_CONDOR, StrategyType.STRADDLE, new BigDecimal("3000"));

            when(morphHistoryJpaRepository.findByChildStrategyId("PARENT")).thenReturn(Optional.empty());
            when(morphHistoryJpaRepository.findByParentStrategyId("PARENT")).thenReturn(List.of(child1, child2));
            when(morphHistoryJpaRepository.findByParentStrategyId("CHILD1")).thenReturn(List.of());
            when(morphHistoryJpaRepository.findByParentStrategyId("CHILD2")).thenReturn(List.of());

            StrategyLineageTree tree = strategyLineageService.getLineageTree("PARENT");

            assertThat(tree.getDescendants()).hasSize(2);
        }
    }

    // ========================
    // CUMULATIVE P&L
    // ========================

    @Nested
    @DisplayName("getCumulativePnl()")
    class CumulativePnl {

        @Test
        @DisplayName("should return ZERO with no ancestors")
        void noAncestors() {
            when(morphHistoryJpaRepository.findByChildStrategyId("S1")).thenReturn(Optional.empty());

            BigDecimal pnl = strategyLineageService.getCumulativePnl("S1");

            assertThat(pnl).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("should sum P&L across ancestor chain")
        void sumAcrossChain() {
            MorphHistoryEntity parentLink = buildEntity(
                    1L,
                    "PARENT",
                    "CHILD",
                    StrategyType.IRON_CONDOR,
                    StrategyType.BULL_PUT_SPREAD,
                    new BigDecimal("5000"));
            MorphHistoryEntity grandparentLink = buildEntity(
                    2L,
                    "GRANDPARENT",
                    "PARENT",
                    StrategyType.STRADDLE,
                    StrategyType.IRON_CONDOR,
                    new BigDecimal("-2000"));

            when(morphHistoryJpaRepository.findByChildStrategyId("CHILD")).thenReturn(Optional.of(parentLink));
            when(morphHistoryJpaRepository.findByChildStrategyId("PARENT")).thenReturn(Optional.of(grandparentLink));
            when(morphHistoryJpaRepository.findByChildStrategyId("GRANDPARENT")).thenReturn(Optional.empty());

            BigDecimal pnl = strategyLineageService.getCumulativePnl("CHILD");

            // 5000 + (-2000) = 3000
            assertThat(pnl).isEqualByComparingTo(new BigDecimal("3000"));
        }

        @Test
        @DisplayName("should handle null parentPnlAtMorph gracefully")
        void nullPnl() {
            MorphHistoryEntity parentLink =
                    buildEntity(1L, "PARENT", "CHILD", StrategyType.IRON_CONDOR, StrategyType.BULL_PUT_SPREAD, null);

            when(morphHistoryJpaRepository.findByChildStrategyId("CHILD")).thenReturn(Optional.of(parentLink));
            when(morphHistoryJpaRepository.findByChildStrategyId("PARENT")).thenReturn(Optional.empty());

            BigDecimal pnl = strategyLineageService.getCumulativePnl("CHILD");

            assertThat(pnl).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ========================
    // HELPERS
    // ========================

    private MorphHistoryEntity buildEntity(
            Long id,
            String parentId,
            String childId,
            StrategyType parentType,
            StrategyType childType,
            BigDecimal parentPnl) {
        return MorphHistoryEntity.builder()
                .id(id)
                .parentStrategyId(parentId)
                .childStrategyId(childId)
                .parentStrategyType(parentType)
                .childStrategyType(childType)
                .parentPnlAtMorph(parentPnl)
                .morphReason("Test morph")
                .morphedAt(LocalDateTime.now())
                .build();
    }
}
