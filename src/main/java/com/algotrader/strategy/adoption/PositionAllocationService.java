package com.algotrader.strategy.adoption;

import com.algotrader.entity.StrategyLegEntity;
import com.algotrader.repository.jpa.StrategyLegJpaRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Computes position allocation across strategies using strategy legs as the source of truth.
 *
 * <p>With the position-strategy linking redesign, positions are strategy-unaware.
 * The only link is {@code StrategyLeg.positionId} + {@code StrategyLeg.quantity}.
 * This service answers: "how much of each position is allocated to strategies?"
 *
 * <p><b>Unmanaged quantity</b> = {@code |position.quantity| - sum(|leg.quantity|)} for all
 * legs referencing that position. A position is fully unmanaged when no legs reference it,
 * partially managed when some quantity is allocated, and fully managed when all quantity
 * is allocated.
 *
 * <p>Used by:
 * <ul>
 *   <li>Broker positions endpoint — to include allocatedQuantity in the response</li>
 *   <li>Adopt validation — to ensure new allocations don't exceed available quantity</li>
 *   <li>FE badge display — Unmanaged / Partial / Fully Managed / Over-allocated</li>
 * </ul>
 */
@Service
public class PositionAllocationService {

    private final StrategyLegJpaRepository strategyLegJpaRepository;

    public PositionAllocationService(StrategyLegJpaRepository strategyLegJpaRepository) {
        this.strategyLegJpaRepository = strategyLegJpaRepository;
    }

    /**
     * Returns the total allocated quantity for a single position.
     * Allocated quantity = sum of leg.quantity for all legs referencing this position.
     * The result preserves sign (negative for short allocations).
     *
     * @param positionId the position ID
     * @return total allocated quantity (0 if no legs reference this position)
     */
    public int getAllocatedQuantity(String positionId) {
        List<StrategyLegEntity> legs = strategyLegJpaRepository.findByPositionId(positionId);
        return legs.stream().mapToInt(StrategyLegEntity::getQuantity).sum();
    }

    /**
     * Returns the unmanaged quantity for a position: how much is NOT yet allocated to strategies.
     * Computed as {@code |positionQuantity| - |allocatedQuantity|}.
     *
     * <p>Returns negative if over-allocated (broker position shrank after allocation).
     *
     * @param positionId       the position ID
     * @param positionQuantity the broker's current quantity for this position (signed)
     * @return unmanaged quantity (absolute value, 0 = fully managed, negative = over-allocated)
     */
    public int getUnmanagedQuantity(String positionId, int positionQuantity) {
        int allocated = getAllocatedQuantity(positionId);
        return Math.abs(positionQuantity) - Math.abs(allocated);
    }

    /**
     * Bulk query: returns allocated quantity per position ID for a set of positions.
     * Uses a single batch query to H2 for efficiency.
     *
     * @param positionIds the position IDs to query
     * @return map of positionId → allocatedQuantity (signed sum of leg quantities)
     */
    public Map<String, Integer> getAllocationMap(Collection<String> positionIds) {
        if (positionIds.isEmpty()) {
            return Map.of();
        }

        List<StrategyLegEntity> legs = strategyLegJpaRepository.findByPositionIdIn(positionIds);

        Map<String, Integer> allocationMap = new HashMap<>();
        for (StrategyLegEntity leg : legs) {
            allocationMap.merge(leg.getPositionId(), leg.getQuantity(), Integer::sum);
        }

        return allocationMap;
    }

    /**
     * Returns allocated quantity for ALL positions that have any allocation.
     * Queries all legs with non-null positionId. Useful for the broker positions
     * endpoint where we need allocations for every position.
     *
     * @return map of positionId → allocatedQuantity for all allocated positions
     */
    public Map<String, Integer> getAllAllocations() {
        List<StrategyLegEntity> legs = strategyLegJpaRepository.findByPositionIdIsNotNull();

        Map<String, Integer> allocationMap = new HashMap<>();
        for (StrategyLegEntity leg : legs) {
            allocationMap.merge(leg.getPositionId(), leg.getQuantity(), Integer::sum);
        }

        return allocationMap;
    }
}
