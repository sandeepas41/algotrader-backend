package com.algotrader.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.algotrader.core.engine.StrategyEngine;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.PositionMismatch;
import com.algotrader.domain.model.ReconciliationResult;
import com.algotrader.domain.model.Tick;
import com.algotrader.event.OrderEvent;
import com.algotrader.event.OrderEventType;
import com.algotrader.event.ReconciliationEvent;
import com.algotrader.event.TickEvent;
import com.algotrader.observability.CustomMetricsService;
import com.algotrader.risk.AccountRiskChecker;
import com.algotrader.session.SessionHealthService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Tests for CustomMetricsService verifying that all 7 custom metrics are
 * correctly registered and updated in response to events and gauge polling.
 *
 * <p>Lenient strictness because gauge registration during construction calls the
 * mock suppliers, making them appear "unnecessary" in nested tests that don't
 * re-invoke those specific mocks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomMetricsServiceTest {

    private MeterRegistry meterRegistry;
    private CustomMetricsService customMetricsService;

    @Mock
    private AccountRiskChecker accountRiskChecker;

    @Mock
    private StrategyEngine strategyEngine;

    @Mock
    private SessionHealthService sessionHealthService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        when(accountRiskChecker.getDailyRealisedPnl()).thenReturn(BigDecimal.ZERO);
        when(strategyEngine.getActiveStrategyCount()).thenReturn(0);
        when(sessionHealthService.isSessionActive()).thenReturn(false);
        customMetricsService =
                new CustomMetricsService(meterRegistry, accountRiskChecker, strategyEngine, sessionHealthService);
    }

    @Nested
    @DisplayName("Counter metrics")
    class CounterMetrics {

        @Test
        @DisplayName("orders.placed.count increments on PLACED event")
        void ordersPlacedCounterIncrements() {
            Order order = Order.builder()
                    .brokerOrderId("ORD-001")
                    .tradingSymbol("NIFTY25FEB24500CE")
                    .side(OrderSide.BUY)
                    .type(OrderType.LIMIT)
                    .status(OrderStatus.OPEN)
                    .build();

            customMetricsService.onOrderEvent(new OrderEvent(this, order, OrderEventType.PLACED));
            customMetricsService.onOrderEvent(new OrderEvent(this, order, OrderEventType.PLACED));

            assertThat(meterRegistry.get("orders.placed.count").counter().count())
                    .isEqualTo(2.0);
        }

        @Test
        @DisplayName("orders.failed.count increments on REJECTED event")
        void ordersFailedCounterIncrements() {
            Order order = Order.builder()
                    .brokerOrderId("ORD-002")
                    .tradingSymbol("NIFTY25FEB24500PE")
                    .side(OrderSide.SELL)
                    .type(OrderType.MARKET)
                    .status(OrderStatus.REJECTED)
                    .rejectionReason("Insufficient margin")
                    .build();

            customMetricsService.onOrderEvent(new OrderEvent(this, order, OrderEventType.REJECTED));

            assertThat(meterRegistry.get("orders.failed.count").counter().count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("order counters ignore non-PLACED/REJECTED events")
        void orderCountersIgnoreOtherEvents() {
            Order order = Order.builder()
                    .brokerOrderId("ORD-003")
                    .status(OrderStatus.COMPLETE)
                    .build();

            customMetricsService.onOrderEvent(new OrderEvent(this, order, OrderEventType.FILLED));
            customMetricsService.onOrderEvent(new OrderEvent(this, order, OrderEventType.CANCELLED));

            assertThat(meterRegistry.get("orders.placed.count").counter().count())
                    .isEqualTo(0.0);
            assertThat(meterRegistry.get("orders.failed.count").counter().count())
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("reconciliation.mismatches increments by mismatch count")
        void reconciliationMismatchCounterIncrements() {
            ReconciliationResult result = ReconciliationResult.builder()
                    .trigger("SCHEDULED")
                    .mismatches(List.of(
                            PositionMismatch.builder()
                                    .tradingSymbol("NIFTY25FEB24500CE")
                                    .build(),
                            PositionMismatch.builder()
                                    .tradingSymbol("NIFTY25FEB24500PE")
                                    .build()))
                    .localPositionCount(2)
                    .brokerPositionCount(3)
                    .build();

            customMetricsService.onReconciliationEvent(new ReconciliationEvent(this, result, false));

            assertThat(meterRegistry.get("reconciliation.mismatches").counter().count())
                    .isEqualTo(2.0);
        }

        @Test
        @DisplayName("reconciliation.mismatches does not increment on zero mismatches")
        void reconciliationMismatchCounterNoIncrementOnMatch() {
            ReconciliationResult result = ReconciliationResult.builder()
                    .trigger("MANUAL")
                    .mismatches(List.of())
                    .localPositionCount(2)
                    .brokerPositionCount(2)
                    .build();

            customMetricsService.onReconciliationEvent(new ReconciliationEvent(this, result, true));

            assertThat(meterRegistry.get("reconciliation.mismatches").counter().count())
                    .isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Gauge metrics")
    class GaugeMetrics {

        @Test
        @DisplayName("daily.pnl gauge returns current P&L value")
        void dailyPnlGaugeReturnsCurrentValue() {
            when(accountRiskChecker.getDailyRealisedPnl()).thenReturn(BigDecimal.valueOf(-25000));

            double value = meterRegistry.get("daily.pnl").gauge().value();
            assertThat(value).isEqualTo(-25000.0);
        }

        @Test
        @DisplayName("active.strategies gauge returns current count")
        void activeStrategiesGaugeReturnsCount() {
            when(strategyEngine.getActiveStrategyCount()).thenReturn(4);

            double value = meterRegistry.get("active.strategies").gauge().value();
            assertThat(value).isEqualTo(4.0);
        }

        @Test
        @DisplayName("kite.session.state gauge returns 1 when active")
        void kiteSessionStateGaugeReturns1WhenActive() {
            when(sessionHealthService.isSessionActive()).thenReturn(true);

            double value = meterRegistry.get("kite.session.state").gauge().value();
            assertThat(value).isEqualTo(1.0);
        }

        @Test
        @DisplayName("kite.session.state gauge returns 0 when inactive")
        void kiteSessionStateGaugeReturns0WhenInactive() {
            when(sessionHealthService.isSessionActive()).thenReturn(false);

            double value = meterRegistry.get("kite.session.state").gauge().value();
            assertThat(value).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Timer metrics")
    class TimerMetrics {

        @Test
        @DisplayName("tick.latency timer records processing duration")
        void tickLatencyTimerRecordsDuration() {
            Tick tick = Tick.builder()
                    .instrumentToken(12345L)
                    .lastPrice(BigDecimal.valueOf(24500))
                    .build();

            TickEvent event = new TickEvent(this, tick);
            customMetricsService.onTickEvent(event);

            assertThat(meterRegistry.get("tick.latency").timer().count()).isEqualTo(1);
            assertThat(meterRegistry.get("tick.latency").timer().totalTime(TimeUnit.NANOSECONDS))
                    .isGreaterThan(0);
        }

        @Test
        @DisplayName("tick.latency timer accumulates multiple recordings")
        void tickLatencyTimerAccumulates() {
            Tick tick = Tick.builder()
                    .instrumentToken(12345L)
                    .lastPrice(BigDecimal.valueOf(24500))
                    .build();

            customMetricsService.onTickEvent(new TickEvent(this, tick));
            customMetricsService.onTickEvent(new TickEvent(this, tick));
            customMetricsService.onTickEvent(new TickEvent(this, tick));

            assertThat(meterRegistry.get("tick.latency").timer().count()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Metric registration")
    class MetricRegistration {

        @Test
        @DisplayName("all 7 custom metrics are registered in MeterRegistry")
        void allMetricsRegistered() {
            assertThat(meterRegistry.find("orders.placed.count").counter()).isNotNull();
            assertThat(meterRegistry.find("orders.failed.count").counter()).isNotNull();
            assertThat(meterRegistry.find("reconciliation.mismatches").counter())
                    .isNotNull();
            assertThat(meterRegistry.find("tick.latency").timer()).isNotNull();
            assertThat(meterRegistry.find("daily.pnl").gauge()).isNotNull();
            assertThat(meterRegistry.find("active.strategies").gauge()).isNotNull();
            assertThat(meterRegistry.find("kite.session.state").gauge()).isNotNull();
        }
    }
}
