package com.algotrader.domain.model;

import com.algotrader.domain.enums.ActionType;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/**
 * Defines what action to take when an adjustment rule triggers.
 *
 * <p>The parameters map holds action-specific configuration. For example:
 * <ul>
 *   <li>ROLL_UP: {"strikes": 2} — roll 2 strikes higher</li>
 *   <li>REDUCE_SIZE: {"percentage": 50} — close 50% of position</li>
 *   <li>ADD_HEDGE: {"optionType": "PE", "offset": 3} — buy PE 3 strikes OTM</li>
 * </ul>
 */
@Data
@Builder
public class AdjustmentAction {

    private ActionType type;
    private Map<String, Object> parameters;
}
