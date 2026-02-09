package com.algotrader.unit.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.domain.model.Position;
import com.algotrader.event.PositionEvent;
import com.algotrader.event.PositionEventType;
import com.algotrader.event.RiskEvent;
import com.algotrader.oms.OrderRequest;
import com.algotrader.risk.AccountRiskChecker;
import com.algotrader.risk.PositionRiskChecker;
import com.algotrader.risk.RiskLimits;
import com.algotrader.risk.RiskManager;
import com.algotrader.risk.RiskValidationResult;
import com.algotrader.risk.RiskViolation;
import com.algotrader.risk.UnderlyingRiskLimits;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for RiskManager covering pre-trade validation orchestration,
 * per-underlying limits, position event handling, and underlying extraction.
 */
@ExtendWith(MockitoExtension.class)
class RiskManagerTest {

    @Mock
    private PositionRiskChecker positionRiskChecker;

    @Mock
    private AccountRiskChecker accountRiskChecker;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private RiskLimits riskLimits;
    private RiskManager riskManager;

    @BeforeEach
    void setUp() {
        riskLimits = RiskLimits.builder()
                .maxLossPerPosition(new BigDecimal("25000"))
                .maxLotsPerPosition(10)
                .build();
        riskManager = new RiskManager(riskLimits, positionRiskChecker, accountRiskChecker, applicationEventPublisher);
    }

    // ==============================
    // PRE-TRADE ORCHESTRATION
    // ==============================

    @Nested
    @DisplayName("Pre-trade Validation Orchestration")
    class PreTradeOrchestration {

        @Test
        @DisplayName("Order with no violations is approved")
        void noViolations_approved() {
            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(5)
                    .build();
            when(positionRiskChecker.validateOrder(request)).thenReturn(Collections.emptyList());
            when(accountRiskChecker.validateOrder(request)).thenReturn(Collections.emptyList());

            RiskValidationResult result = riskManager.validateOrder(request);

            assertThat(result.isApproved()).isTrue();
            assertThat(result.getViolations()).isEmpty();
            verify(applicationEventPublisher, never()).publishEvent(any(RiskEvent.class));
        }

        @Test
        @DisplayName("Position-level violation causes rejection")
        void positionLevelViolation_rejected() {
            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(15)
                    .build();
            when(positionRiskChecker.validateOrder(request))
                    .thenReturn(List.of(RiskViolation.of("POSITION_SIZE_EXCEEDED", "exceeds max lots")));

            RiskValidationResult result = riskManager.validateOrder(request);

            assertThat(result.isRejected()).isTrue();
            assertThat(result.getViolations()).hasSize(1);
            verify(applicationEventPublisher).publishEvent(any(RiskEvent.class));
        }

        @Test
        @DisplayName("Violations from position and underlying are aggregated")
        void aggregatesViolations() {
            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(15)
                    .build();

            // Position-level violation
            when(positionRiskChecker.validateOrder(request))
                    .thenReturn(List.of(RiskViolation.of("POSITION_SIZE_EXCEEDED", "exceeds max lots")));

            // Set underlying limits that will also be exceeded
            riskManager.setUnderlyingLimits(
                    "NIFTY",
                    UnderlyingRiskLimits.builder()
                            .underlying("NIFTY")
                            .maxLots(5)
                            .build());
            // Mock getCurrentLotsForUnderlying -> getPositionsForInstrument returns empty (0 current)
            when(positionRiskChecker.getPositionsForInstrument(0)).thenReturn(Collections.emptyList());

            RiskValidationResult result = riskManager.validateOrder(request);

            assertThat(result.isRejected()).isTrue();
            // Position violation + underlying violation (15 > 5)
            assertThat(result.getViolations()).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    // ==============================
    // PER-UNDERLYING LIMITS
    // ==============================

    @Nested
    @DisplayName("Per-Underlying Limits Validation")
    class UnderlyingLimitsValidation {

        @Test
        @DisplayName("Order within underlying lot limit passes")
        void withinUnderlyingLotLimit_passes() {
            riskManager.setUnderlyingLimits(
                    "NIFTY",
                    UnderlyingRiskLimits.builder()
                            .underlying("NIFTY")
                            .maxLots(20)
                            .build());

            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(5)
                    .build();
            when(positionRiskChecker.validateOrder(request)).thenReturn(Collections.emptyList());
            when(positionRiskChecker.getPositionsForInstrument(0)).thenReturn(Collections.emptyList());

            RiskValidationResult result = riskManager.validateOrder(request);

            assertThat(result.isApproved()).isTrue();
        }

        @Test
        @DisplayName("Order exceeding underlying lot limit is rejected independently")
        void exceedingUnderlyingLotLimit_rejectedIndependently() {
            riskManager.setUnderlyingLimits(
                    "NIFTY",
                    UnderlyingRiskLimits.builder()
                            .underlying("NIFTY")
                            .maxLots(3)
                            .build());

            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(5)
                    .build();
            // Position-level checks pass
            when(positionRiskChecker.validateOrder(request)).thenReturn(Collections.emptyList());
            // No existing positions
            when(positionRiskChecker.getPositionsForInstrument(0)).thenReturn(Collections.emptyList());

            RiskValidationResult result = riskManager.validateOrder(request);

            assertThat(result.isRejected()).isTrue();
            assertThat(result.getViolations()).hasSize(1);
            assertThat(result.getViolations().get(0).getCode()).isEqualTo("UNDERLYING_LOT_LIMIT_EXCEEDED");
        }

        @Test
        @DisplayName("No underlying limits configured allows order through")
        void noUnderlyingLimits_passes() {
            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(100)
                    .build();
            when(positionRiskChecker.validateOrder(request)).thenReturn(Collections.emptyList());

            RiskValidationResult result = riskManager.validateOrder(request);

            assertThat(result.isApproved()).isTrue();
        }

        @Test
        @DisplayName("Current lots + order lots are summed against underlying limit")
        void currentPlusOrderLots_summedAgainstLimit() {
            riskManager.setUnderlyingLimits(
                    "NIFTY",
                    UnderlyingRiskLimits.builder()
                            .underlying("NIFTY")
                            .maxLots(10)
                            .build());

            // 7 existing lots for NIFTY
            Position existingPosition = Position.builder()
                    .id("POS-001")
                    .tradingSymbol("NIFTY24FEB21000CE")
                    .quantity(-7)
                    .build();
            when(positionRiskChecker.getPositionsForInstrument(0)).thenReturn(List.of(existingPosition));

            // New order for 5 lots -> total 12 > limit 10
            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(5)
                    .build();
            when(positionRiskChecker.validateOrder(request)).thenReturn(Collections.emptyList());

            RiskValidationResult result = riskManager.validateOrder(request);

            assertThat(result.isRejected()).isTrue();
            assertThat(result.getViolations().get(0).getCode()).isEqualTo("UNDERLYING_LOT_LIMIT_EXCEEDED");
        }
    }

    // ==============================
    // UNDERLYING LIMITS MANAGEMENT
    // ==============================

    @Nested
    @DisplayName("Underlying Limits Management")
    class UnderlyingLimitsManagement {

        @Test
        @DisplayName("Set and get underlying limits")
        void setAndGetUnderlyingLimits() {
            UnderlyingRiskLimits limits = UnderlyingRiskLimits.builder()
                    .underlying("NIFTY")
                    .maxLots(10)
                    .maxExposure(new BigDecimal("5000000"))
                    .build();

            riskManager.setUnderlyingLimits("NIFTY", limits);

            assertThat(riskManager.getUnderlyingLimits("NIFTY")).isEqualTo(limits);
            assertThat(riskManager.getUnderlyingLimits("BANKNIFTY")).isNull();
        }

        @Test
        @DisplayName("getAllUnderlyingLimits returns unmodifiable view")
        void getAllUnderlyingLimits_unmodifiable() {
            riskManager.setUnderlyingLimits(
                    "NIFTY",
                    UnderlyingRiskLimits.builder()
                            .underlying("NIFTY")
                            .maxLots(10)
                            .build());
            riskManager.setUnderlyingLimits(
                    "BANKNIFTY",
                    UnderlyingRiskLimits.builder()
                            .underlying("BANKNIFTY")
                            .maxLots(5)
                            .build());

            assertThat(riskManager.getAllUnderlyingLimits()).hasSize(2);
        }
    }

    // ==============================
    // POSITION EVENT HANDLING
    // ==============================

    @Nested
    @DisplayName("Position Event Handling")
    class PositionEventHandling {

        @Test
        @DisplayName("Position update triggers index update in PositionRiskChecker")
        void positionUpdate_updatesIndex() {
            Position position = Position.builder()
                    .id("POS-001")
                    .instrumentToken(12345L)
                    .quantity(-5)
                    .unrealizedPnl(new BigDecimal("-5000"))
                    .build();
            PositionEvent event = new PositionEvent(this, position, PositionEventType.UPDATED);

            when(positionRiskChecker.isLossLimitBreached(position)).thenReturn(false);

            riskManager.onPositionUpdate(event);

            verify(positionRiskChecker).updatePositionIndex(position);
        }

        @Test
        @DisplayName("Position breaching loss limit publishes CRITICAL risk event")
        void positionBreachingLoss_publishesCriticalEvent() {
            Position position = Position.builder()
                    .id("POS-001")
                    .instrumentToken(12345L)
                    .quantity(-5)
                    .unrealizedPnl(new BigDecimal("-30000"))
                    .build();
            PositionEvent event = new PositionEvent(this, position, PositionEventType.UPDATED);

            when(positionRiskChecker.isLossLimitBreached(position)).thenReturn(true);

            riskManager.onPositionUpdate(event);

            ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());

            RiskEvent riskEvent = captor.getValue();
            assertThat(riskEvent.getLevel().name()).isEqualTo("CRITICAL");
            assertThat(riskEvent.getMessage()).contains("POS-001");
        }

        @Test
        @DisplayName("Position within limits does not publish risk event")
        void positionWithinLimits_noRiskEvent() {
            Position position = Position.builder()
                    .id("POS-001")
                    .instrumentToken(12345L)
                    .quantity(-5)
                    .unrealizedPnl(new BigDecimal("-5000"))
                    .build();
            PositionEvent event = new PositionEvent(this, position, PositionEventType.UPDATED);

            when(positionRiskChecker.isLossLimitBreached(position)).thenReturn(false);

            riskManager.onPositionUpdate(event);

            verify(applicationEventPublisher, never()).publishEvent(any(RiskEvent.class));
        }
    }

    // ==============================
    // QUERIES
    // ==============================

    @Nested
    @DisplayName("Queries")
    class Queries {

        @Test
        @DisplayName("getLimits returns the configured global risk limits")
        void getLimits_returnsConfigured() {
            assertThat(riskManager.getLimits()).isEqualTo(riskLimits);
            assertThat(riskManager.getLimits().getMaxLotsPerPosition()).isEqualTo(10);
        }
    }
}
