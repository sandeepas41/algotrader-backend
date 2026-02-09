package com.algotrader.morph;

import com.algotrader.domain.model.StrategyLineage;
import com.algotrader.domain.model.StrategyLineageTree;
import com.algotrader.mapper.MorphHistoryMapper;
import com.algotrader.repository.jpa.MorphHistoryJpaRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.mapstruct.factory.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for querying strategy lineage (parent-child morph relationships).
 *
 * <p>Every morph creates a parent-child lineage link. This service enables:
 * <ul>
 *   <li>Building the full lineage tree (ancestors + descendants)</li>
 *   <li>Calculating cumulative P&L across the entire morph chain</li>
 *   <li>Tracing the origin of a morphed strategy back to its root</li>
 * </ul>
 *
 * <p>Uses recursive traversal to walk the lineage graph. Since morph chains
 * are typically short (2-5 levels deep), recursion is safe and readable.
 */
@Service
public class StrategyLineageService {

    private static final Logger log = LoggerFactory.getLogger(StrategyLineageService.class);

    private final MorphHistoryJpaRepository morphHistoryJpaRepository;
    private final MorphHistoryMapper morphHistoryMapper = Mappers.getMapper(MorphHistoryMapper.class);

    public StrategyLineageService(MorphHistoryJpaRepository morphHistoryJpaRepository) {
        this.morphHistoryJpaRepository = morphHistoryJpaRepository;
    }

    /**
     * Builds the full lineage tree for a strategy (ancestors and descendants).
     *
     * @param strategyId the strategy to trace
     * @return tree with ancestor and descendant lineage records
     */
    public StrategyLineageTree getLineageTree(String strategyId) {
        List<StrategyLineage> ancestors = findAncestors(strategyId);
        List<StrategyLineage> descendants = findDescendants(strategyId);

        return StrategyLineageTree.builder()
                .strategyId(strategyId)
                .ancestors(ancestors)
                .descendants(descendants)
                .build();
    }

    /**
     * Calculates cumulative P&L across the entire lineage chain.
     *
     * <p>Sums up parentPnlAtMorph from all ancestor records. This gives the
     * total P&L that was "carried over" through morphs. Does NOT include the
     * current strategy's live P&L (that's added by the caller).
     *
     * @param strategyId the strategy to calculate for
     * @return cumulative P&L from all ancestor morphs
     */
    public BigDecimal getCumulativePnl(String strategyId) {
        List<StrategyLineage> ancestors = findAncestors(strategyId);

        BigDecimal cumulative = BigDecimal.ZERO;
        for (StrategyLineage ancestor : ancestors) {
            if (ancestor.getParentPnlAtMorph() != null) {
                cumulative = cumulative.add(ancestor.getParentPnlAtMorph());
            }
        }

        return cumulative;
    }

    /**
     * Returns all lineage records, ordered by morph time descending.
     */
    public List<StrategyLineage> getAllLineage() {
        return morphHistoryMapper.toDomainList(morphHistoryJpaRepository.findAllByOrderByMorphedAtDesc());
    }

    // ========================
    // TREE TRAVERSAL
    // ========================

    /**
     * Walks up the lineage tree: child -> parent -> grandparent -> ...
     */
    List<StrategyLineage> findAncestors(String strategyId) {
        List<StrategyLineage> ancestors = new ArrayList<>();
        String currentId = strategyId;

        while (true) {
            Optional<StrategyLineage> parent =
                    morphHistoryJpaRepository.findByChildStrategyId(currentId).map(morphHistoryMapper::toDomain);

            if (parent.isEmpty()) {
                break;
            }

            ancestors.add(parent.get());
            currentId = parent.get().getParentStrategyId();
        }

        return ancestors;
    }

    /**
     * Walks down the lineage tree: parent -> children -> grandchildren -> ...
     * A parent can have multiple children (one-to-many morph).
     */
    List<StrategyLineage> findDescendants(String strategyId) {
        List<StrategyLineage> direct =
                morphHistoryMapper.toDomainList(morphHistoryJpaRepository.findByParentStrategyId(strategyId));

        List<StrategyLineage> all = new ArrayList<>(direct);
        for (StrategyLineage child : direct) {
            all.addAll(findDescendants(child.getChildStrategyId()));
        }

        return all;
    }
}
