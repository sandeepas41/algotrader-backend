package com.algotrader.event;

import com.algotrader.domain.model.Position;
import java.math.BigDecimal;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a position state changes (opened, updated, closed, etc.).
 *
 * <p>Position events are published by the PositionService when order fills
 * create/update positions, or when tick-driven P&L recalculations produce
 * material changes.
 *
 * <p>Key listeners:
 * <ul>
 *   <li>StrategyService — updates strategy state based on leg position changes</li>
 *   <li>RiskManager — validates position limits and triggers alerts</li>
 *   <li>AlertService — sends P&L threshold notifications</li>
 *   <li>WebSocketHandler — pushes position updates to frontend</li>
 * </ul>
 */
public class PositionEvent extends ApplicationEvent {

    private final Position position;
    private final PositionEventType eventType;
    private final BigDecimal previousPnl;

    /**
     * @param source      the component publishing this event
     * @param position    the current position snapshot
     * @param eventType   what kind of position change occurred
     * @param previousPnl the position's unrealized P&L before this change (null if not applicable)
     */
    public PositionEvent(Object source, Position position, PositionEventType eventType, BigDecimal previousPnl) {
        super(source);
        this.position = position;
        this.eventType = eventType;
        this.previousPnl = previousPnl;
    }

    public PositionEvent(Object source, Position position, PositionEventType eventType) {
        this(source, position, eventType, null);
    }

    public Position getPosition() {
        return position;
    }

    public PositionEventType getEventType() {
        return eventType;
    }

    /**
     * The position's unrealized P&L before this event. Useful for calculating
     * P&L deltas (e.g., for daily loss limit tracking). Null for OPENED events.
     */
    public BigDecimal getPreviousPnl() {
        return previousPnl;
    }
}
