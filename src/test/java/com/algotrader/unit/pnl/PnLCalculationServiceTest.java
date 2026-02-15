package com.algotrader.unit.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.algotrader.domain.model.Position;
import com.algotrader.domain.model.Trade;
import com.algotrader.domain.vo.ChargeBreakdown;
import com.algotrader.entity.TradeEntity;
import com.algotrader.mapper.TradeMapper;
import com.algotrader.pnl.ChargeCalculator;
import com.algotrader.pnl.PnLCalculationService;
import com.algotrader.repository.jpa.TradeJpaRepository;
import com.algotrader.repository.redis.PositionRedisRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for PnLCalculationService covering unrealized P&L (position-level),
 * strategy-level P&L, account-level daily P&L, and charge aggregation.
 */
@ExtendWith(MockitoExtension.class)
class PnLCalculationServiceTest {

    @Mock
    private PositionRedisRepository positionRedisRepository;

    @Mock
    private TradeJpaRepository tradeJpaRepository;

    @Mock
    private TradeMapper tradeMapper;

    @Mock
    private ChargeCalculator chargeCalculator;

    private PnLCalculationService pnLCalculationService;

    @BeforeEach
    void setUp() {
        pnLCalculationService =
                new PnLCalculationService(positionRedisRepository, tradeJpaRepository, tradeMapper, chargeCalculator);
    }

    // ==============================
    // UNREALIZED P&L
    // ==============================

    @Nested
    @DisplayName("Unrealized P&L Calculation")
    class UnrealizedPnL {

        @Test
        @DisplayName("Long position with profit: (lastPrice - avgPrice) * qty")
        void longPositionProfit() {
            Position position = Position.builder()
                    .id("POS-001")
                    .averagePrice(new BigDecimal("100"))
                    .lastPrice(new BigDecimal("110"))
                    .quantity(5)
                    .build();

            BigDecimal pnl = pnLCalculationService.calculateUnrealizedPnl(position);

            // (110 - 100) * 5 = 50
            assertThat(pnl).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("Long position with loss: negative P&L")
        void longPositionLoss() {
            Position position = Position.builder()
                    .id("POS-002")
                    .averagePrice(new BigDecimal("200"))
                    .lastPrice(new BigDecimal("180"))
                    .quantity(10)
                    .build();

            BigDecimal pnl = pnLCalculationService.calculateUnrealizedPnl(position);

            // (180 - 200) * 10 = -200
            assertThat(pnl).isEqualByComparingTo("-200.00");
        }

        @Test
        @DisplayName("Short position with profit: quantity is negative, so P&L is positive")
        void shortPositionProfit() {
            Position position = Position.builder()
                    .id("POS-003")
                    .averagePrice(new BigDecimal("200"))
                    .lastPrice(new BigDecimal("180"))
                    .quantity(-5) // short
                    .build();

            BigDecimal pnl = pnLCalculationService.calculateUnrealizedPnl(position);

            // (180 - 200) * (-5) = 100
            assertThat(pnl).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("Short position with loss: negative P&L")
        void shortPositionLoss() {
            Position position = Position.builder()
                    .id("POS-004")
                    .averagePrice(new BigDecimal("100"))
                    .lastPrice(new BigDecimal("120"))
                    .quantity(-5) // short
                    .build();

            BigDecimal pnl = pnLCalculationService.calculateUnrealizedPnl(position);

            // (120 - 100) * (-5) = -100
            assertThat(pnl).isEqualByComparingTo("-100.00");
        }

        @Test
        @DisplayName("Position with null lastPrice returns zero")
        void nullLastPrice_returnsZero() {
            Position position = Position.builder()
                    .id("POS-005")
                    .averagePrice(new BigDecimal("100"))
                    .lastPrice(null)
                    .quantity(5)
                    .build();

            BigDecimal pnl = pnLCalculationService.calculateUnrealizedPnl(position);

            assertThat(pnl).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("Position with null averagePrice returns zero")
        void nullAvgPrice_returnsZero() {
            Position position = Position.builder()
                    .id("POS-006")
                    .averagePrice(null)
                    .lastPrice(new BigDecimal("100"))
                    .quantity(5)
                    .build();

            BigDecimal pnl = pnLCalculationService.calculateUnrealizedPnl(position);

            assertThat(pnl).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("Zero quantity position returns zero P&L")
        void zeroQuantity_returnsZero() {
            Position position = Position.builder()
                    .id("POS-007")
                    .averagePrice(new BigDecimal("100"))
                    .lastPrice(new BigDecimal("200"))
                    .quantity(0)
                    .build();

            BigDecimal pnl = pnLCalculationService.calculateUnrealizedPnl(position);

            assertThat(pnl).isEqualByComparingTo("0.00");
        }
    }

    // ==============================
    // STRATEGY-LEVEL P&L
    // ==============================

    @Nested
    @DisplayName("Strategy-level P&L")
    class StrategyPnL {

        @Test
        @DisplayName("Strategy unrealized P&L sums across all positions")
        void strategyUnrealized_sumsPositions() {
            Position p1 = Position.builder()
                    .averagePrice(new BigDecimal("100"))
                    .lastPrice(new BigDecimal("110"))
                    .quantity(5)
                    .build();
            Position p2 = Position.builder()
                    .averagePrice(new BigDecimal("200"))
                    .lastPrice(new BigDecimal("180"))
                    .quantity(-3)
                    .build();

            BigDecimal unrealized = pnLCalculationService.calculateStrategyUnrealizedPnl(List.of(p1, p2));

            // p1: (110-100)*5 = 50, p2: (180-200)*(-3) = 60, total = 110
            assertThat(unrealized).isEqualByComparingTo("110.00");
        }

        @Test
        @DisplayName("Strategy with no positions returns zero unrealized")
        void noPositions_returnsZero() {
            BigDecimal unrealized = pnLCalculationService.calculateStrategyUnrealizedPnl(Collections.emptyList());

            assertThat(unrealized).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("Strategy realized P&L sums trade P&L values")
        void strategyRealized_sumsTradePnl() {
            TradeEntity te1 = TradeEntity.builder().id("T1").build();
            TradeEntity te2 = TradeEntity.builder().id("T2").build();
            Trade t1 = Trade.builder().id("T1").pnl(new BigDecimal("500")).build();
            Trade t2 = Trade.builder().id("T2").pnl(new BigDecimal("-200")).build();

            when(tradeJpaRepository.findByStrategyId("STR-001")).thenReturn(List.of(te1, te2));
            when(tradeMapper.toDomainList(List.of(te1, te2))).thenReturn(List.of(t1, t2));

            BigDecimal realized = pnLCalculationService.calculateStrategyRealizedPnl("STR-001");

            assertThat(realized).isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("Strategy realized P&L skips trades with null P&L (entry trades)")
        void nullPnlTrades_skipped() {
            TradeEntity te1 = TradeEntity.builder().id("T1").build();
            Trade t1 = Trade.builder().id("T1").pnl(null).build();

            when(tradeJpaRepository.findByStrategyId("STR-001")).thenReturn(List.of(te1));
            when(tradeMapper.toDomainList(List.of(te1))).thenReturn(List.of(t1));

            BigDecimal realized = pnLCalculationService.calculateStrategyRealizedPnl("STR-001");

            assertThat(realized).isEqualByComparingTo("0.00");
        }
    }

    // ==============================
    // STRATEGY CHARGES
    // ==============================

    @Nested
    @DisplayName("Strategy Charges Aggregation")
    class StrategyCharges {

        @Test
        @DisplayName("Strategy charges aggregate across all trades")
        void aggregatesAcrossTrades() {
            ChargeBreakdown c1 = ChargeBreakdown.builder()
                    .brokerage(new BigDecimal("20"))
                    .stt(new BigDecimal("5"))
                    .exchangeCharges(new BigDecimal("10"))
                    .sebiCharges(new BigDecimal("0.01"))
                    .stampDuty(new BigDecimal("0.30"))
                    .gst(new BigDecimal("3.60"))
                    .build();
            ChargeBreakdown c2 = ChargeBreakdown.builder()
                    .brokerage(new BigDecimal("20"))
                    .stt(new BigDecimal("8"))
                    .exchangeCharges(new BigDecimal("12"))
                    .sebiCharges(new BigDecimal("0.02"))
                    .stampDuty(new BigDecimal("0.50"))
                    .gst(new BigDecimal("5.76"))
                    .build();

            TradeEntity te1 = TradeEntity.builder().id("T1").build();
            TradeEntity te2 = TradeEntity.builder().id("T2").build();
            Trade t1 = Trade.builder().id("T1").charges(c1).build();
            Trade t2 = Trade.builder().id("T2").charges(c2).build();

            when(tradeJpaRepository.findByStrategyId("STR-001")).thenReturn(List.of(te1, te2));
            when(tradeMapper.toDomainList(List.of(te1, te2))).thenReturn(List.of(t1, t2));

            ChargeBreakdown total = pnLCalculationService.calculateStrategyCharges("STR-001");

            assertThat(total.getBrokerage()).isEqualByComparingTo("40");
            assertThat(total.getStt()).isEqualByComparingTo("13");
            assertThat(total.getExchangeCharges()).isEqualByComparingTo("22");
            assertThat(total.getSebiCharges()).isEqualByComparingTo("0.03");
            assertThat(total.getStampDuty()).isEqualByComparingTo("0.80");
            assertThat(total.getGst()).isEqualByComparingTo("9.36");
        }

        @Test
        @DisplayName("Trades with null charges are skipped")
        void nullCharges_skipped() {
            TradeEntity te1 = TradeEntity.builder().id("T1").build();
            Trade t1 = Trade.builder().id("T1").charges(null).build();

            when(tradeJpaRepository.findByStrategyId("STR-001")).thenReturn(List.of(te1));
            when(tradeMapper.toDomainList(List.of(te1))).thenReturn(List.of(t1));

            ChargeBreakdown total = pnLCalculationService.calculateStrategyCharges("STR-001");

            assertThat(total.getTotal()).isEqualByComparingTo("0");
        }
    }

    // ==============================
    // DAILY P&L (ACCOUNT-WIDE)
    // ==============================

    @Nested
    @DisplayName("Account-level Daily P&L")
    class DailyPnL {

        @Test
        @DisplayName("Daily P&L = realized from trades + unrealized from positions")
        void dailyPnl_realizedPlusUnrealized() {
            when(tradeJpaRepository.getDailyRealizedPnl(LocalDate.now().atStartOfDay()))
                    .thenReturn(new BigDecimal("5000"));

            Position p1 = Position.builder()
                    .averagePrice(new BigDecimal("100"))
                    .lastPrice(new BigDecimal("110"))
                    .quantity(10)
                    .build();
            when(positionRedisRepository.findAll()).thenReturn(List.of(p1));

            BigDecimal daily = pnLCalculationService.calculateDailyPnl();

            // Realized = 5000, Unrealized = (110-100)*10 = 100
            assertThat(daily).isEqualByComparingTo("5100.00");
        }

        @Test
        @DisplayName("Daily P&L skips closed positions (zero quantity)")
        void skipsClosedPositions() {
            when(tradeJpaRepository.getDailyRealizedPnl(LocalDate.now().atStartOfDay()))
                    .thenReturn(BigDecimal.ZERO);

            Position closed = Position.builder()
                    .averagePrice(new BigDecimal("100"))
                    .lastPrice(new BigDecimal("200"))
                    .quantity(0)
                    .build();
            when(positionRedisRepository.findAll()).thenReturn(List.of(closed));

            BigDecimal daily = pnLCalculationService.calculateDailyPnl();

            assertThat(daily).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("Daily P&L with no trades and no positions is zero")
        void noTradesNoPositions_zero() {
            when(tradeJpaRepository.getDailyRealizedPnl(LocalDate.now().atStartOfDay()))
                    .thenReturn(BigDecimal.ZERO);
            when(positionRedisRepository.findAll()).thenReturn(Collections.emptyList());

            BigDecimal daily = pnLCalculationService.calculateDailyPnl();

            assertThat(daily).isEqualByComparingTo("0.00");
        }
    }

    // ==============================
    // TOTAL UNREALIZED
    // ==============================

    @Nested
    @DisplayName("Total Unrealized P&L")
    class TotalUnrealized {

        @Test
        @DisplayName("Total unrealized sums all open positions")
        void totalUnrealized_sumsAll() {
            Position p1 = Position.builder()
                    .averagePrice(new BigDecimal("100"))
                    .lastPrice(new BigDecimal("110"))
                    .quantity(5)
                    .build();
            Position p2 = Position.builder()
                    .averagePrice(new BigDecimal("200"))
                    .lastPrice(new BigDecimal("190"))
                    .quantity(-10)
                    .build();

            when(positionRedisRepository.findAll()).thenReturn(List.of(p1, p2));

            BigDecimal total = pnLCalculationService.calculateTotalUnrealizedPnl();

            // p1: (110-100)*5 = 50, p2: (190-200)*(-10) = 100, total = 150
            assertThat(total).isEqualByComparingTo("150.00");
        }
    }
}
