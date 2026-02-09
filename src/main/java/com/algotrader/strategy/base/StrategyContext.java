package com.algotrader.strategy.base;

import com.algotrader.event.EventPublisherHelper;
import com.algotrader.oms.JournaledMultiLegExecutor;
import com.algotrader.service.InstrumentService;
import lombok.Builder;
import lombok.Getter;

/**
 * Runtime services injected into each strategy instance.
 *
 * <p>Strategies use this to interact with platform services without depending
 * on Spring directly. The StrategyEngine builds a StrategyContext when deploying
 * a strategy and passes it via {@link BaseStrategy#setServices}.
 *
 * <p>Design rationale: Strategies are plain Java objects (not Spring beans), so
 * they can't use {@code @Autowired}. StrategyContext bundles the services they
 * need. Adding a new service to the context is the extension point for
 * giving strategies new capabilities.
 *
 * <p>Note: DecisionLogger and CooldownService from the spec are deferred to
 * Phase 8 (Task 8.1) and Phase 7. For now, decision logging goes through
 * EventPublisherHelper.publishDecision(), and cooldown is managed in-memory
 * via BaseStrategy.lastAdjustmentTime.
 */
@Getter
@Builder
public class StrategyContext {

    /** Event publishing for decisions, lifecycle, and alerts. */
    private final EventPublisherHelper eventPublisherHelper;

    /** Multi-leg order executor with WAL journaling. */
    private final JournaledMultiLegExecutor journaledMultiLegExecutor;

    /** Instrument lookup (token resolution, lot sizes, expiry info). */
    private final InstrumentService instrumentService;

    // #TODO Phase 7.4: Add PositionSizer (FixedLotSizer, PercentageOfCapitalSizer, RiskBasedSizer)
    // #TODO Phase 8.1: Add DecisionLogger for structured decision logging
    // #TODO Phase 7: Add CooldownService (Redis-backed cooldown tracking)
}
