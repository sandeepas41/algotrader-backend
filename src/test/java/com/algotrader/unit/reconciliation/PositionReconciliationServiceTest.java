package com.algotrader.unit.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.MismatchType;
import com.algotrader.domain.enums.ResolutionStrategy;
import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.PositionMismatch;
import com.algotrader.domain.model.ReconciliationResult;
import com.algotrader.event.ReconciliationEvent;
import com.algotrader.observability.DecisionLogger;
import com.algotrader.repository.redis.PositionRedisRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Tests for PositionReconciliationService covering mismatch detection,
 * resolution strategies, scheduled gating, and event publishing.
 */
@ExtendWith(MockitoExtension.class)
class PositionReconciliationServiceTest {

    @Mock
    private BrokerGateway brokerGateway;

    @Mock
    private PositionRedisRepository positionRedisRepository;

    @Mock
    private StrategyEngine strategyEngine;

    @Mock
    private TradingCalendarService tradingCalendarService;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private DecisionLogger decisionLogger;

    private com.algotrader.reconciliation.PositionReconciliationService positionReconciliationService;

    @BeforeEach
    void setUp() {
        positionReconciliationService = new com.algotrader.reconciliation.PositionReconciliationService(
                brokerGateway,
                positionRedisRepository,
                strategyEngine,
                tradingCalendarService,
                applicationEventPublisher,
                decisionLogger);
    }

    @Test
    @DisplayName("No mismatches when broker and local positions match exactly")
    void noMismatches_whenPositionsMatch() {
        Position brokerPos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(120.50))
                .build();
        Position localPos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(120.50))
                .build();

        when(brokerGateway.getPositions()).thenReturn(Map.of("net", List.of(brokerPos)));
        when(positionRedisRepository.findAll()).thenReturn(List.of(localPos));

        ReconciliationResult result = positionReconciliationService.reconcile("MANUAL");

        assertThat(result.hasMismatches()).isFalse();
        assertThat(result.getBrokerPositionCount()).isEqualTo(1);
        assertThat(result.getLocalPositionCount()).isEqualTo(1);
        assertThat(result.getTrigger()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("QUANTITY_MISMATCH detected when broker and local quantities differ")
    void quantityMismatch_detectedAndAutoSynced() {
        Position brokerPos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-75)
                .averagePrice(BigDecimal.valueOf(120.50))
                .build();
        Position localPos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(118.00))
                .build();

        when(brokerGateway.getPositions()).thenReturn(Map.of("net", List.of(brokerPos)));
        when(positionRedisRepository.findAll()).thenReturn(List.of(localPos));

        ReconciliationResult result = positionReconciliationService.reconcile("SCHEDULED");

        assertThat(result.hasMismatches()).isTrue();
        assertThat(result.getTotalMismatches()).isEqualTo(1);

        PositionMismatch mismatch = result.getMismatches().get(0);
        assertThat(mismatch.getType()).isEqualTo(MismatchType.QUANTITY_MISMATCH);
        assertThat(mismatch.getResolution()).isEqualTo(ResolutionStrategy.AUTO_SYNC);
        assertThat(mismatch.getBrokerQuantity()).isEqualTo(-75);
        assertThat(mismatch.getLocalQuantity()).isEqualTo(-50);
        assertThat(result.getAutoSynced()).isEqualTo(1);

        // Verify position was synced from broker
        verify(positionRedisRepository).save(brokerPos);
    }

    @Test
    @DisplayName("QUANTITY_MISMATCH always uses AUTO_SYNC resolution")
    void quantityMismatch_alwaysAutoSyncs() {
        Position brokerPos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-75)
                .averagePrice(BigDecimal.valueOf(120.50))
                .build();
        Position localPos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(118.00))
                .build();

        when(brokerGateway.getPositions()).thenReturn(Map.of("net", List.of(brokerPos)));
        when(positionRedisRepository.findAll()).thenReturn(List.of(localPos));

        ReconciliationResult result = positionReconciliationService.reconcile("MANUAL");

        assertThat(result.hasMismatches()).isTrue();
        PositionMismatch mismatch = result.getMismatches().get(0);
        assertThat(mismatch.getResolution()).isEqualTo(ResolutionStrategy.AUTO_SYNC);
        assertThat(result.getAutoSynced()).isEqualTo(1);

        // Verify position was synced from broker
        verify(positionRedisRepository).save(brokerPos);
    }

    @Test
    @DisplayName("MISSING_LOCAL detected when broker has position not in Redis")
    void missingLocal_detectedAndAutoSynced() {
        Position brokerPos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(120.50))
                .build();

        when(brokerGateway.getPositions()).thenReturn(Map.of("net", List.of(brokerPos)));
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        ReconciliationResult result = positionReconciliationService.reconcile("STARTUP");

        assertThat(result.hasMismatches()).isTrue();
        PositionMismatch mismatch = result.getMismatches().get(0);
        assertThat(mismatch.getType()).isEqualTo(MismatchType.MISSING_LOCAL);
        assertThat(mismatch.getResolution()).isEqualTo(ResolutionStrategy.AUTO_SYNC);
        assertThat(result.getAutoSynced()).isEqualTo(1);

        verify(positionRedisRepository).save(brokerPos);
    }

    @Test
    @DisplayName("MISSING_BROKER detected when local has position not at broker")
    void missingBroker_removesStaleLocalPosition() {
        Position localPos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(120.50))
                .build();

        when(brokerGateway.getPositions()).thenReturn(Map.of("net", List.of()));
        when(positionRedisRepository.findAll()).thenReturn(List.of(localPos));

        ReconciliationResult result = positionReconciliationService.reconcile("MANUAL");

        assertThat(result.hasMismatches()).isTrue();
        PositionMismatch mismatch = result.getMismatches().get(0);
        assertThat(mismatch.getType()).isEqualTo(MismatchType.MISSING_BROKER);
        assertThat(mismatch.getResolution()).isEqualTo(ResolutionStrategy.AUTO_SYNC);
        assertThat(result.getAutoSynced()).isEqualTo(1);

        verify(positionRedisRepository).delete("12345");
    }

    @Test
    @DisplayName("PRICE_DRIFT detected when average prices differ by >2%")
    void priceDrift_alertOnly() {
        Position brokerPos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(130.00))
                .build();
        Position localPos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(120.00))
                .build();

        when(brokerGateway.getPositions()).thenReturn(Map.of("net", List.of(brokerPos)));
        when(positionRedisRepository.findAll()).thenReturn(List.of(localPos));

        ReconciliationResult result = positionReconciliationService.reconcile("MANUAL");

        assertThat(result.hasMismatches()).isTrue();
        PositionMismatch mismatch = result.getMismatches().get(0);
        assertThat(mismatch.getType()).isEqualTo(MismatchType.PRICE_DRIFT);
        assertThat(mismatch.getResolution()).isEqualTo(ResolutionStrategy.ALERT_ONLY);
        assertThat(result.getAlertsRaised()).isEqualTo(1);

        // ALERT_ONLY should NOT sync or pause
        verify(positionRedisRepository, never()).save(any(Position.class));
        verify(strategyEngine, never()).pauseStrategy(anyString());
    }

    @Test
    @DisplayName("No PRICE_DRIFT when drift is under 2% threshold")
    void noPriceDrift_underThreshold() {
        Position brokerPos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(120.50))
                .build();
        Position localPos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(-50)
                .averagePrice(BigDecimal.valueOf(120.00))
                .build();

        when(brokerGateway.getPositions()).thenReturn(Map.of("net", List.of(brokerPos)));
        when(positionRedisRepository.findAll()).thenReturn(List.of(localPos));

        ReconciliationResult result = positionReconciliationService.reconcile("MANUAL");

        // 0.42% drift is under the 2% threshold
        assertThat(result.hasMismatches()).isFalse();
    }

    @Test
    @DisplayName("Scheduled reconciliation skips when market is closed")
    void scheduled_skipsOutsideMarketHours() {
        when(tradingCalendarService.isMarketOpen()).thenReturn(false);

        positionReconciliationService.scheduledReconciliation();

        verify(brokerGateway, never()).getPositions();
    }

    @Test
    @DisplayName("Scheduled reconciliation runs when market is open")
    void scheduled_runsWhenMarketOpen() {
        when(tradingCalendarService.isMarketOpen()).thenReturn(true);
        when(brokerGateway.getPositions()).thenReturn(Map.of("net", List.of()));
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        positionReconciliationService.scheduledReconciliation();

        verify(brokerGateway).getPositions();
    }

    @Test
    @DisplayName("Zero-quantity broker positions are filtered out")
    void zeroQuantityBrokerPositions_ignored() {
        Position closedPos = Position.builder()
                .instrumentToken(12345L)
                .tradingSymbol("NIFTY25FEB24500CE")
                .quantity(0)
                .averagePrice(BigDecimal.ZERO)
                .build();

        when(brokerGateway.getPositions()).thenReturn(Map.of("net", List.of(closedPos)));
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        ReconciliationResult result = positionReconciliationService.reconcile("MANUAL");

        assertThat(result.getBrokerPositionCount()).isEqualTo(0);
        assertThat(result.hasMismatches()).isFalse();
    }

    @Test
    @DisplayName("ReconciliationEvent is published after each run")
    void publishesReconciliationEvent() {
        when(brokerGateway.getPositions()).thenReturn(Map.of("net", List.of()));
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        positionReconciliationService.reconcile("MANUAL");

        ArgumentCaptor<ReconciliationEvent> captor = ArgumentCaptor.forClass(ReconciliationEvent.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());

        ReconciliationEvent event = captor.getValue();
        assertThat(event.isManual()).isTrue();
        assertThat(event.getResult().getTrigger()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("Manual reconcile delegates to reconcile with MANUAL trigger")
    void manualReconcile_delegatesToReconcile() {
        when(brokerGateway.getPositions()).thenReturn(Map.of("net", List.of()));
        when(positionRedisRepository.findAll()).thenReturn(List.of());

        ReconciliationResult result = positionReconciliationService.manualReconcile();

        assertThat(result.getTrigger()).isEqualTo("MANUAL");
    }
}
