package com.algotrader.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Processing priority for orders in the order queue.
 *
 * <p>Orders are dequeued by priority level (lower number = higher priority),
 * then by FIFO within the same level. Kill switch orders always execute first
 * to ensure emergency exits are never delayed by pending entry orders.
 *
 * <p>Priority levels:
 * <ul>
 *   <li>KILL_SWITCH (0): Emergency exit -- bypasses all checks</li>
 *   <li>RISK_EXIT (1): Risk-driven forced exits (loss limit, margin call)</li>
 *   <li>STRATEGY_EXIT (2): Strategy exit (target/stoploss reached)</li>
 *   <li>STRATEGY_ADJUSTMENT (3): Strategy adjustments (roll, hedge)</li>
 *   <li>STRATEGY_ENTRY (4): Strategy entry orders</li>
 *   <li>MANUAL (5): Manual orders from UI/API</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum OrderPriority {
    KILL_SWITCH(0, "Emergency kill switch exit orders"),
    RISK_EXIT(1, "Risk-driven forced exits (loss limit, margin call)"),
    STRATEGY_EXIT(2, "Strategy exit orders (target/stoploss reached)"),
    STRATEGY_ADJUSTMENT(3, "Strategy adjustment orders (roll, hedge)"),
    STRATEGY_ENTRY(4, "Strategy entry orders"),
    MANUAL(5, "Manual orders from UI/API");

    private final int level;
    private final String description;
}
