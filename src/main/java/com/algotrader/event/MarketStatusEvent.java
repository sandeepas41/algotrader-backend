package com.algotrader.event;

import com.algotrader.domain.enums.MarketPhase;
import java.time.LocalDateTime;
import org.springframework.context.ApplicationEvent;

/**
 * Published when the market transitions between phases (e.g., PRE_OPEN -> NORMAL).
 *
 * <p>Market phase transitions are detected by the MarketStatusPublisher (a scheduled
 * service that monitors market hours) and published for system-wide behavior adaptation.
 *
 * <p>Key listeners:
 * <ul>
 *   <li>StrategyEngine — only evaluates tick conditions during NORMAL phase</li>
 *   <li>InstrumentService — refreshes instrument cache on transition to NORMAL</li>
 *   <li>RiskManager — resets daily counters on CLOSED -> PRE_OPEN transition</li>
 *   <li>DailyPnLAggregator — persists daily P&L summary on transition to CLOSED</li>
 *   <li>WebSocketHandler — pushes market status to frontend</li>
 * </ul>
 */
public class MarketStatusEvent extends ApplicationEvent {

    private final MarketPhase previousPhase;
    private final MarketPhase currentPhase;
    private final LocalDateTime transitionTime;

    public MarketStatusEvent(Object source, MarketPhase previousPhase, MarketPhase currentPhase) {
        super(source);
        this.previousPhase = previousPhase;
        this.currentPhase = currentPhase;
        this.transitionTime = LocalDateTime.now();
    }

    public MarketPhase getPreviousPhase() {
        return previousPhase;
    }

    public MarketPhase getCurrentPhase() {
        return currentPhase;
    }

    public LocalDateTime getTransitionTime() {
        return transitionTime;
    }
}
