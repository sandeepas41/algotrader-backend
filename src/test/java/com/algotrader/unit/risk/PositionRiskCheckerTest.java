package com.algotrader.unit.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.algotrader.domain.model.Position;
import com.algotrader.oms.OrderRequest;
import com.algotrader.repository.redis.PositionRedisRepository;
import com.algotrader.risk.PositionRiskChecker;
import com.algotrader.risk.RiskLimits;
import com.algotrader.risk.RiskViolation;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for PositionRiskChecker covering pre-trade validation (max lots,
 * max notional), real-time loss/profit monitoring, and the instrument-aware
 * position index.
 */
@ExtendWith(MockitoExtension.class)
class PositionRiskCheckerTest {

    @Mock
    private PositionRedisRepository positionRedisRepository;

    private RiskLimits riskLimits;
    private PositionRiskChecker positionRiskChecker;

    @BeforeEach
    void setUp() {
        riskLimits = RiskLimits.builder()
                .maxLotsPerPosition(10)
                .maxPositionValue(new BigDecimal("500000"))
                .maxLossPerPosition(new BigDecimal("25000"))
                .maxProfitPerPosition(new BigDecimal("50000"))
                .build();
        positionRiskChecker = new PositionRiskChecker(riskLimits, positionRedisRepository);
    }

    // ==============================
    // PRE-TRADE VALIDATION
    // ==============================

    @Nested
    @DisplayName("Pre-trade: Max Lots Per Position")
    class MaxLotsValidation {

        @Test
        @DisplayName("Order within max lots passes validation")
        void orderWithinMaxLots_passes() {
            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(5)
                    .price(new BigDecimal("200"))
                    .build();

            List<RiskViolation> violations = positionRiskChecker.validateOrder(request);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Order exactly at max lots passes validation")
        void orderAtExactMaxLots_passes() {
            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(10)
                    .price(new BigDecimal("200"))
                    .build();

            List<RiskViolation> violations = positionRiskChecker.validateOrder(request);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Order exceeding max lots is rejected")
        void orderExceedingMaxLots_rejected() {
            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(15)
                    .price(new BigDecimal("200"))
                    .build();

            List<RiskViolation> violations = positionRiskChecker.validateOrder(request);

            assertThat(violations).hasSize(1);
            assertThat(violations.get(0).getCode()).isEqualTo("POSITION_SIZE_EXCEEDED");
        }

        @Test
        @DisplayName("Null maxLotsPerPosition disables the check")
        void nullMaxLots_checkDisabled() {
            riskLimits.setMaxLotsPerPosition(null);

            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(1000)
                    .price(new BigDecimal("200"))
                    .build();

            List<RiskViolation> violations = positionRiskChecker.validateOrder(request);

            // Only position value check may trigger, not lot check
            assertThat(violations).noneMatch(v -> v.getCode().equals("POSITION_SIZE_EXCEEDED"));
        }
    }

    @Nested
    @DisplayName("Pre-trade: Max Position Value")
    class MaxPositionValueValidation {

        @Test
        @DisplayName("Order within max position value passes")
        void orderWithinMaxValue_passes() {
            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(5)
                    .price(new BigDecimal("200"))
                    .build();

            List<RiskViolation> violations = positionRiskChecker.validateOrder(request);

            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("Order exceeding max position value is rejected")
        void orderExceedingMaxValue_rejected() {
            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(5)
                    .price(new BigDecimal("200000"))
                    .build();

            List<RiskViolation> violations = positionRiskChecker.validateOrder(request);

            assertThat(violations).anyMatch(v -> v.getCode().equals("POSITION_VALUE_EXCEEDED"));
        }

        @Test
        @DisplayName("Null price skips value check")
        void nullPrice_checksSkipped() {
            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(5)
                    .build();

            List<RiskViolation> violations = positionRiskChecker.validateOrder(request);

            assertThat(violations).noneMatch(v -> v.getCode().equals("POSITION_VALUE_EXCEEDED"));
        }

        @Test
        @DisplayName("Multiple violations returned together")
        void multipleViolations_allReturned() {
            OrderRequest request = OrderRequest.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .quantity(15)
                    .price(new BigDecimal("200000"))
                    .build();

            List<RiskViolation> violations = positionRiskChecker.validateOrder(request);

            assertThat(violations).hasSize(2);
            assertThat(violations)
                    .extracting(RiskViolation::getCode)
                    .containsExactlyInAnyOrder("POSITION_SIZE_EXCEEDED", "POSITION_VALUE_EXCEEDED");
        }
    }

    // ==============================
    // LOSS / PROFIT MONITORING
    // ==============================

    @Nested
    @DisplayName("Loss Limit Breach Detection")
    class LossLimitBreach {

        @Test
        @DisplayName("Position with loss within limit is not breached")
        void lossWithinLimit_notBreached() {
            Position position = Position.builder()
                    .id("POS-001")
                    .unrealizedPnl(new BigDecimal("-10000"))
                    .build();

            assertThat(positionRiskChecker.isLossLimitBreached(position)).isFalse();
        }

        @Test
        @DisplayName("Position with loss exactly at limit is breached")
        void lossExactlyAtLimit_breached() {
            // maxLossPerPosition = 25000, so breach at pnl <= -25000
            Position position = Position.builder()
                    .id("POS-001")
                    .unrealizedPnl(new BigDecimal("-25000"))
                    .build();

            assertThat(positionRiskChecker.isLossLimitBreached(position)).isTrue();
        }

        @Test
        @DisplayName("Position with loss exceeding limit is breached")
        void lossExceedingLimit_breached() {
            Position position = Position.builder()
                    .id("POS-001")
                    .unrealizedPnl(new BigDecimal("-30000"))
                    .build();

            assertThat(positionRiskChecker.isLossLimitBreached(position)).isTrue();
        }

        @Test
        @DisplayName("Null unrealizedPnl is not breached")
        void nullPnl_notBreached() {
            Position position = Position.builder().id("POS-001").build();

            assertThat(positionRiskChecker.isLossLimitBreached(position)).isFalse();
        }

        @Test
        @DisplayName("Null maxLossPerPosition disables loss check")
        void nullMaxLoss_checkDisabled() {
            riskLimits.setMaxLossPerPosition(null);
            Position position = Position.builder()
                    .id("POS-001")
                    .unrealizedPnl(new BigDecimal("-999999"))
                    .build();

            assertThat(positionRiskChecker.isLossLimitBreached(position)).isFalse();
        }

        @Test
        @DisplayName("Profitable position is not breached")
        void profitablePosition_notBreached() {
            Position position = Position.builder()
                    .id("POS-001")
                    .unrealizedPnl(new BigDecimal("10000"))
                    .build();

            assertThat(positionRiskChecker.isLossLimitBreached(position)).isFalse();
        }
    }

    @Nested
    @DisplayName("Profit Target Detection")
    class ProfitTargetReached {

        @Test
        @DisplayName("Position below profit target is not reached")
        void belowTarget_notReached() {
            Position position = Position.builder()
                    .id("POS-001")
                    .unrealizedPnl(new BigDecimal("30000"))
                    .build();

            assertThat(positionRiskChecker.isProfitTargetReached(position)).isFalse();
        }

        @Test
        @DisplayName("Position exactly at profit target is reached")
        void exactlyAtTarget_reached() {
            Position position = Position.builder()
                    .id("POS-001")
                    .unrealizedPnl(new BigDecimal("50000"))
                    .build();

            assertThat(positionRiskChecker.isProfitTargetReached(position)).isTrue();
        }

        @Test
        @DisplayName("Position above profit target is reached")
        void aboveTarget_reached() {
            Position position = Position.builder()
                    .id("POS-001")
                    .unrealizedPnl(new BigDecimal("75000"))
                    .build();

            assertThat(positionRiskChecker.isProfitTargetReached(position)).isTrue();
        }

        @Test
        @DisplayName("Null maxProfitPerPosition disables profit check")
        void nullMaxProfit_checkDisabled() {
            riskLimits.setMaxProfitPerPosition(null);
            Position position = Position.builder()
                    .id("POS-001")
                    .unrealizedPnl(new BigDecimal("999999"))
                    .build();

            assertThat(positionRiskChecker.isProfitTargetReached(position)).isFalse();
        }
    }

    // ==============================
    // INSTRUMENT INDEX
    // ==============================

    @Nested
    @DisplayName("Instrument-Aware Position Index")
    class InstrumentIndex {

        @Test
        @DisplayName("Position added to index is retrievable by instrument token")
        void addPosition_retrievable() {
            Position position = Position.builder()
                    .id("POS-001")
                    .instrumentToken(12345L)
                    .quantity(-5)
                    .build();

            positionRiskChecker.updatePositionIndex(position);

            List<Position> positions = positionRiskChecker.getPositionsForInstrument(12345L);
            assertThat(positions).hasSize(1);
            assertThat(positions.get(0).getId()).isEqualTo("POS-001");
        }

        @Test
        @DisplayName("Multiple positions for same instrument are all indexed")
        void multiplePositions_sameInstrument() {
            Position pos1 = Position.builder()
                    .id("POS-001")
                    .instrumentToken(12345L)
                    .quantity(-5)
                    .build();
            Position pos2 = Position.builder()
                    .id("POS-002")
                    .instrumentToken(12345L)
                    .quantity(3)
                    .build();

            positionRiskChecker.updatePositionIndex(pos1);
            positionRiskChecker.updatePositionIndex(pos2);

            List<Position> positions = positionRiskChecker.getPositionsForInstrument(12345L);
            assertThat(positions).hasSize(2);
        }

        @Test
        @DisplayName("Positions for different instruments are indexed separately")
        void differentInstruments_indexedSeparately() {
            Position niftyPos = Position.builder()
                    .id("POS-001")
                    .instrumentToken(12345L)
                    .quantity(-5)
                    .build();
            Position bankNiftyPos = Position.builder()
                    .id("POS-002")
                    .instrumentToken(67890L)
                    .quantity(3)
                    .build();

            positionRiskChecker.updatePositionIndex(niftyPos);
            positionRiskChecker.updatePositionIndex(bankNiftyPos);

            assertThat(positionRiskChecker.getPositionsForInstrument(12345L)).hasSize(1);
            assertThat(positionRiskChecker.getPositionsForInstrument(67890L)).hasSize(1);
            assertThat(positionRiskChecker.getIndexedPositionCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Updating a position replaces the old entry in the index")
        void updatePosition_replacesOld() {
            Position original = Position.builder()
                    .id("POS-001")
                    .instrumentToken(12345L)
                    .quantity(-5)
                    .unrealizedPnl(new BigDecimal("-1000"))
                    .build();
            positionRiskChecker.updatePositionIndex(original);

            Position updated = Position.builder()
                    .id("POS-001")
                    .instrumentToken(12345L)
                    .quantity(-5)
                    .unrealizedPnl(new BigDecimal("-5000"))
                    .build();
            positionRiskChecker.updatePositionIndex(updated);

            List<Position> positions = positionRiskChecker.getPositionsForInstrument(12345L);
            assertThat(positions).hasSize(1);
            assertThat(positions.get(0).getUnrealizedPnl()).isEqualByComparingTo("-5000");
        }

        @Test
        @DisplayName("Closed position (quantity=0) is removed from index")
        void closedPosition_removedFromIndex() {
            Position open = Position.builder()
                    .id("POS-001")
                    .instrumentToken(12345L)
                    .quantity(-5)
                    .build();
            positionRiskChecker.updatePositionIndex(open);
            assertThat(positionRiskChecker.getPositionsForInstrument(12345L)).hasSize(1);

            Position closed = Position.builder()
                    .id("POS-001")
                    .instrumentToken(12345L)
                    .quantity(0)
                    .build();
            positionRiskChecker.updatePositionIndex(closed);

            assertThat(positionRiskChecker.getPositionsForInstrument(12345L)).isEmpty();
            assertThat(positionRiskChecker.getIndexedPositionCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Position with null instrumentToken is ignored")
        void nullInstrumentToken_ignored() {
            Position position = Position.builder().id("POS-001").quantity(-5).build();

            positionRiskChecker.updatePositionIndex(position);

            assertThat(positionRiskChecker.getIndexedPositionCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("removeFromIndex removes position across all instruments")
        void removeFromIndex_removesAcrossInstruments() {
            Position position = Position.builder()
                    .id("POS-001")
                    .instrumentToken(12345L)
                    .quantity(-5)
                    .build();
            positionRiskChecker.updatePositionIndex(position);

            positionRiskChecker.removeFromIndex("POS-001");

            assertThat(positionRiskChecker.getPositionsForInstrument(12345L)).isEmpty();
        }

        @Test
        @DisplayName("Unknown instrument token returns empty list")
        void unknownToken_emptyList() {
            assertThat(positionRiskChecker.getPositionsForInstrument(99999L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Instrument-Aware Checking")
    class InstrumentAwareChecking {

        @Test
        @DisplayName("checkPositionsForInstrument only evaluates positions for that token")
        void checksOnlyRelevantPositions() {
            // Position on instrument 12345 breaching loss limit
            Position breaching = Position.builder()
                    .id("POS-001")
                    .instrumentToken(12345L)
                    .quantity(-5)
                    .unrealizedPnl(new BigDecimal("-30000"))
                    .build();
            // Position on instrument 67890 within limits
            Position safe = Position.builder()
                    .id("POS-002")
                    .instrumentToken(67890L)
                    .quantity(3)
                    .unrealizedPnl(new BigDecimal("-5000"))
                    .build();

            positionRiskChecker.updatePositionIndex(breaching);
            positionRiskChecker.updatePositionIndex(safe);

            // Checking instrument 12345 should find the breach
            List<Position> breachedFor12345 = positionRiskChecker.checkPositionsForInstrument(12345L);
            assertThat(breachedFor12345).hasSize(1);
            assertThat(breachedFor12345.get(0).getId()).isEqualTo("POS-001");

            // Checking instrument 67890 should find no breaches
            List<Position> breachedFor67890 = positionRiskChecker.checkPositionsForInstrument(67890L);
            assertThat(breachedFor67890).isEmpty();
        }

        @Test
        @DisplayName("checkAllPositions scans all positions from Redis")
        void checkAllPositions_scansRedis() {
            Position breaching = Position.builder()
                    .id("POS-001")
                    .unrealizedPnl(new BigDecimal("-30000"))
                    .build();
            Position safe = Position.builder()
                    .id("POS-002")
                    .unrealizedPnl(new BigDecimal("-5000"))
                    .build();
            when(positionRedisRepository.findAll()).thenReturn(List.of(breaching, safe));

            List<Position> breached = positionRiskChecker.checkAllPositions();

            assertThat(breached).hasSize(1);
            assertThat(breached.get(0).getId()).isEqualTo("POS-001");
        }
    }
}
