package com.algotrader.reconciliation;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.DecisionOutcome;
import com.algotrader.domain.enums.DecisionSeverity;
import com.algotrader.domain.enums.DecisionSource;
import com.algotrader.domain.enums.DecisionType;
import com.algotrader.domain.enums.MismatchType;
import com.algotrader.domain.enums.ResolutionStrategy;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.PositionMismatch;
import com.algotrader.domain.model.ReconciliationResult;
import com.algotrader.event.ReconciliationEvent;
import com.algotrader.observability.DecisionLogger;
import com.algotrader.repository.redis.PositionRedisRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Ensures local positions (Redis) stay in sync with the broker's positions (Kite API).
 *
 * <p>Runs every 5 minutes during market hours, on startup, on WebSocket reconnect,
 * and on manual API trigger. Compares by instrument token and applies resolution
 * strategies per mismatch type:
 * <ul>
 *   <li>QUANTITY_MISMATCH with strategy -> PAUSE_STRATEGY + sync</li>
 *   <li>QUANTITY_MISMATCH without strategy -> AUTO_SYNC</li>
 *   <li>MISSING_LOCAL -> AUTO_SYNC (create unmanaged position)</li>
 *   <li>MISSING_BROKER -> AUTO_SYNC (remove stale local position)</li>
 *   <li>PRICE_DRIFT -> ALERT_ONLY (log only, >2% drift)</li>
 * </ul>
 *
 * <p>Every reconciliation run is logged as a DecisionLog entry and publishes a
 * {@link ReconciliationEvent} for downstream handlers (alerts, metrics, WebSocket).
 */
@Service
public class PositionReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PositionReconciliationService.class);

    private static final BigDecimal PRICE_DRIFT_THRESHOLD = new BigDecimal("0.02");

    private final BrokerGateway brokerGateway;
    private final PositionRedisRepository positionRedisRepository;
    private final StrategyEngine strategyEngine;
    private final TradingCalendarService tradingCalendarService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final DecisionLogger decisionLogger;

    public PositionReconciliationService(
            BrokerGateway brokerGateway,
            PositionRedisRepository positionRedisRepository,
            StrategyEngine strategyEngine,
            TradingCalendarService tradingCalendarService,
            ApplicationEventPublisher applicationEventPublisher,
            DecisionLogger decisionLogger) {
        this.brokerGateway = brokerGateway;
        this.positionRedisRepository = positionRedisRepository;
        this.strategyEngine = strategyEngine;
        this.tradingCalendarService = tradingCalendarService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.decisionLogger = decisionLogger;
    }

    /**
     * Scheduled reconciliation every 5 minutes during market hours.
     */
    @Scheduled(fixedRate = 300_000)
    public void scheduledReconciliation() {
        if (!tradingCalendarService.isMarketOpen()) {
            return;
        }
        reconcile("SCHEDULED");
    }

    /**
     * Triggered on WebSocket reconnect to detect missed updates.
     */
    public void onWebSocketReconnect() {
        reconcile("WEBSOCKET_RECONNECT");
    }

    /**
     * Triggered manually via API. Returns result for the response body.
     */
    public ReconciliationResult manualReconcile() {
        return reconcile("MANUAL");
    }

    /**
     * Core reconciliation logic: fetch broker + local, compare, resolve, publish.
     */
    public ReconciliationResult reconcile(String trigger) {
        long startTime = System.currentTimeMillis();
        log.info("Position reconciliation started: trigger={}", trigger);

        ReconciliationResult reconciliationResult = ReconciliationResult.builder()
                .timestamp(LocalDateTime.now())
                .trigger(trigger)
                .build();

        try {
            // Fetch broker positions (source of truth) — use "net" positions
            Map<String, List<Position>> allPositions = brokerGateway.getPositions();
            List<Position> brokerPositions = allPositions.getOrDefault("net", List.of());
            List<Position> activeBrokerPositions =
                    brokerPositions.stream().filter(p -> p.getQuantity() != 0).toList();
            reconciliationResult.setBrokerPositionCount(activeBrokerPositions.size());

            // Fetch local positions from Redis
            List<Position> localPositions = positionRedisRepository.findAll();
            reconciliationResult.setLocalPositionCount(localPositions.size());

            // Index by instrument token for comparison
            Map<Long, Position> brokerMap = activeBrokerPositions.stream()
                    .collect(Collectors.toMap(Position::getInstrumentToken, Function.identity(), (a, b) -> a));

            Map<Long, Position> localMap = localPositions.stream()
                    .collect(Collectors.toMap(Position::getInstrumentToken, Function.identity(), (a, b) -> a));

            List<PositionMismatch> mismatches = new ArrayList<>();

            // Check broker positions against local
            for (Map.Entry<Long, Position> entry : brokerMap.entrySet()) {
                Long token = entry.getKey();
                Position brokerPos = entry.getValue();
                Position localPos = localMap.get(token);

                if (localPos == null) {
                    // MISSING_LOCAL: broker has a position we don't know about
                    mismatches.add(PositionMismatch.builder()
                            .instrumentToken(token)
                            .tradingSymbol(brokerPos.getTradingSymbol())
                            .type(MismatchType.MISSING_LOCAL)
                            .resolution(ResolutionStrategy.AUTO_SYNC)
                            .brokerQuantity(brokerPos.getQuantity())
                            .brokerAveragePrice(brokerPos.getAveragePrice())
                            .localQuantity(0)
                            .build());
                } else if (brokerPos.getQuantity() != localPos.getQuantity()) {
                    // QUANTITY_MISMATCH: strategy-owned positions get paused
                    ResolutionStrategy resolution = localPos.getStrategyId() != null
                            ? ResolutionStrategy.PAUSE_STRATEGY
                            : ResolutionStrategy.AUTO_SYNC;

                    mismatches.add(PositionMismatch.builder()
                            .instrumentToken(token)
                            .tradingSymbol(brokerPos.getTradingSymbol())
                            .type(MismatchType.QUANTITY_MISMATCH)
                            .resolution(resolution)
                            .strategyId(localPos.getStrategyId())
                            .brokerQuantity(brokerPos.getQuantity())
                            .brokerAveragePrice(brokerPos.getAveragePrice())
                            .localQuantity(localPos.getQuantity())
                            .localAveragePrice(localPos.getAveragePrice())
                            .build());
                } else {
                    // Quantities match — check for price drift
                    checkPriceDrift(brokerPos, localPos, mismatches);
                }
            }

            // Check for positions that exist locally but not at broker (stale cache)
            for (Map.Entry<Long, Position> entry : localMap.entrySet()) {
                Long token = entry.getKey();
                if (!brokerMap.containsKey(token)) {
                    Position localPos = entry.getValue();
                    mismatches.add(PositionMismatch.builder()
                            .instrumentToken(token)
                            .tradingSymbol(localPos.getTradingSymbol())
                            .type(MismatchType.MISSING_BROKER)
                            .resolution(ResolutionStrategy.AUTO_SYNC)
                            .strategyId(localPos.getStrategyId())
                            .localQuantity(localPos.getQuantity())
                            .localAveragePrice(localPos.getAveragePrice())
                            .brokerQuantity(0)
                            .build());
                }
            }

            reconciliationResult.setMismatches(mismatches);

            // Apply resolutions
            applyResolutions(mismatches, brokerMap, reconciliationResult);

        } catch (Exception e) {
            log.error("Position reconciliation failed", e);
        }

        reconciliationResult.setDurationMs(System.currentTimeMillis() - startTime);

        // Log as DecisionLog entry
        boolean manual = "MANUAL".equals(trigger);
        decisionLogger.log(
                DecisionSource.RECONCILIATION,
                null,
                DecisionType.RECONCILIATION_RUN,
                reconciliationResult.hasMismatches() ? DecisionOutcome.TRIGGERED : DecisionOutcome.INFO,
                String.format(
                        "Reconciliation %s: %d mismatches, %d auto-synced, %d strategies paused",
                        trigger,
                        reconciliationResult.getTotalMismatches(),
                        reconciliationResult.getAutoSynced(),
                        reconciliationResult.getStrategiesPaused()),
                Map.of(
                        "trigger",
                        trigger,
                        "brokerCount",
                        reconciliationResult.getBrokerPositionCount(),
                        "localCount",
                        reconciliationResult.getLocalPositionCount(),
                        "mismatches",
                        reconciliationResult.getTotalMismatches(),
                        "durationMs",
                        reconciliationResult.getDurationMs()),
                reconciliationResult.hasMismatches() ? DecisionSeverity.WARNING : DecisionSeverity.DEBUG);

        // Publish event
        applicationEventPublisher.publishEvent(new ReconciliationEvent(this, reconciliationResult, manual));

        if (reconciliationResult.hasMismatches()) {
            log.warn(
                    "Reconciliation complete: {} mismatches found, autoSynced={}, paused={}",
                    reconciliationResult.getTotalMismatches(),
                    reconciliationResult.getAutoSynced(),
                    reconciliationResult.getStrategiesPaused());
        } else {
            log.info("Reconciliation complete: no mismatches, duration={}ms", reconciliationResult.getDurationMs());
        }

        return reconciliationResult;
    }

    private void checkPriceDrift(Position brokerPos, Position localPos, List<PositionMismatch> mismatches) {
        if (localPos.getAveragePrice() == null || brokerPos.getAveragePrice() == null) {
            return;
        }
        if (localPos.getAveragePrice().compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        BigDecimal drift = brokerPos
                .getAveragePrice()
                .subtract(localPos.getAveragePrice())
                .abs()
                .divide(localPos.getAveragePrice(), 4, RoundingMode.HALF_UP);

        if (drift.compareTo(PRICE_DRIFT_THRESHOLD) > 0) {
            mismatches.add(PositionMismatch.builder()
                    .instrumentToken(brokerPos.getInstrumentToken())
                    .tradingSymbol(brokerPos.getTradingSymbol())
                    .type(MismatchType.PRICE_DRIFT)
                    .resolution(ResolutionStrategy.ALERT_ONLY)
                    .strategyId(localPos.getStrategyId())
                    .brokerQuantity(brokerPos.getQuantity())
                    .brokerAveragePrice(brokerPos.getAveragePrice())
                    .localQuantity(localPos.getQuantity())
                    .localAveragePrice(localPos.getAveragePrice())
                    .build());
        }
    }

    private void applyResolutions(
            List<PositionMismatch> mismatches,
            Map<Long, Position> brokerMap,
            ReconciliationResult reconciliationResult) {
        int autoSynced = 0;
        int alertsRaised = 0;
        int strategiesPaused = 0;

        for (PositionMismatch mismatch : mismatches) {
            switch (mismatch.getResolution()) {
                case AUTO_SYNC -> {
                    Position brokerPos = brokerMap.get(mismatch.getInstrumentToken());
                    if (brokerPos != null) {
                        positionRedisRepository.save(brokerPos);
                        mismatch.setResolved(true);
                        mismatch.setResolutionDetail("Synced from broker");
                    } else if (mismatch.getType() == MismatchType.MISSING_BROKER) {
                        positionRedisRepository.delete(String.valueOf(mismatch.getInstrumentToken()));
                        mismatch.setResolved(true);
                        mismatch.setResolutionDetail("Removed stale local position");
                    }
                    autoSynced++;
                }
                case ALERT_ONLY -> {
                    mismatch.setResolved(false);
                    mismatch.setResolutionDetail("Alert raised for manual review");
                    alertsRaised++;
                }
                case PAUSE_STRATEGY -> {
                    if (mismatch.getStrategyId() != null) {
                        strategyEngine.pauseStrategy(mismatch.getStrategyId());
                        mismatch.setResolutionDetail("Strategy " + mismatch.getStrategyId() + " paused");
                    }
                    // Also sync the position to broker state
                    Position brokerPos = brokerMap.get(mismatch.getInstrumentToken());
                    if (brokerPos != null) {
                        positionRedisRepository.save(brokerPos);
                    }
                    mismatch.setResolved(true);
                    strategiesPaused++;
                }
            }
        }

        reconciliationResult.setAutoSynced(autoSynced);
        reconciliationResult.setAlertsRaised(alertsRaised);
        reconciliationResult.setStrategiesPaused(strategiesPaused);
    }
}
