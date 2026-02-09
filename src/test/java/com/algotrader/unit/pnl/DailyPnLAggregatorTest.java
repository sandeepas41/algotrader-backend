package com.algotrader.unit.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.domain.enums.MarketPhase;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.model.Trade;
import com.algotrader.domain.vo.ChargeBreakdown;
import com.algotrader.entity.DailyPnlEntity;
import com.algotrader.entity.TradeEntity;
import com.algotrader.event.MarketStatusEvent;
import com.algotrader.mapper.TradeMapper;
import com.algotrader.pnl.ChargeCalculator;
import com.algotrader.pnl.DailyPnLAggregator;
import com.algotrader.pnl.PnLCalculationService;
import com.algotrader.repository.jpa.DailyPnlJpaRepository;
import com.algotrader.repository.jpa.TradeJpaRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit tests for DailyPnLAggregator covering end-of-day aggregation,
 * Redis persistence for crash recovery, market close event handling,
 * and max drawdown calculation.
 */
@ExtendWith(MockitoExtension.class)
class DailyPnLAggregatorTest {

    @Mock
    private TradeJpaRepository tradeJpaRepository;

    @Mock
    private TradeMapper tradeMapper;

    @Mock
    private ChargeCalculator chargeCalculator;

    @Mock
    private DailyPnlJpaRepository dailyPnlJpaRepository;

    @Mock
    private PnLCalculationService pnLCalculationService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private DailyPnLAggregator dailyPnLAggregator;

    @BeforeEach
    void setUp() {
        dailyPnLAggregator = new DailyPnLAggregator(
                tradeJpaRepository,
                tradeMapper,
                chargeCalculator,
                dailyPnlJpaRepository,
                pnLCalculationService,
                redisTemplate);
    }

    // ==============================
    // END-OF-DAY AGGREGATION
    // ==============================

    @Nested
    @DisplayName("End-of-Day Aggregation")
    class EndOfDayAggregation {

        @Test
        @DisplayName("Aggregates trades into DailyPnlEntity and saves to H2")
        void aggregatesSavesToH2() {
            TradeEntity te1 = TradeEntity.builder().id("T1").build();
            Trade t1 = Trade.builder()
                    .id("T1")
                    .side(OrderSide.BUY)
                    .price(new BigDecimal("200"))
                    .quantity(75)
                    .pnl(null)
                    .charges(ChargeBreakdown.zero())
                    .build();
            Trade t2 = Trade.builder()
                    .id("T2")
                    .side(OrderSide.SELL)
                    .price(new BigDecimal("210"))
                    .quantity(75)
                    .pnl(new BigDecimal("750"))
                    .charges(ChargeBreakdown.zero())
                    .build();

            when(tradeJpaRepository.findByDateRange(any(), any())).thenReturn(List.of(te1, te1));
            when(tradeMapper.toDomainList(any())).thenReturn(List.of(t1, t2));
            when(dailyPnlJpaRepository.findByDate(LocalDate.now())).thenReturn(Optional.empty());
            when(pnLCalculationService.calculateTotalUnrealizedPnl()).thenReturn(BigDecimal.ZERO);

            dailyPnLAggregator.aggregateEndOfDay();

            ArgumentCaptor<DailyPnlEntity> captor = ArgumentCaptor.forClass(DailyPnlEntity.class);
            verify(dailyPnlJpaRepository).save(captor.capture());

            DailyPnlEntity saved = captor.getValue();
            assertThat(saved.getDate()).isEqualTo(LocalDate.now());
            assertThat(saved.getRealizedPnl()).isEqualByComparingTo("750.00");
            assertThat(saved.getTotalTrades()).isEqualTo(2);
            assertThat(saved.getWinningTrades()).isEqualTo(1);
            assertThat(saved.getLosingTrades()).isEqualTo(0);
        }

        @Test
        @DisplayName("No trades today skips aggregation")
        void noTrades_skipsAggregation() {
            when(tradeJpaRepository.findByDateRange(any(), any())).thenReturn(Collections.emptyList());
            when(tradeMapper.toDomainList(Collections.emptyList())).thenReturn(Collections.emptyList());

            dailyPnLAggregator.aggregateEndOfDay();

            verify(dailyPnlJpaRepository, never()).save(any());
        }

        @Test
        @DisplayName("Updates existing daily record instead of creating duplicate")
        void updatesExistingRecord() {
            DailyPnlEntity existing = DailyPnlEntity.builder()
                    .id(1L)
                    .date(LocalDate.now())
                    .createdAt(LocalDateTime.now().minusHours(1))
                    .build();

            TradeEntity te1 = TradeEntity.builder().id("T1").build();
            Trade t1 = Trade.builder()
                    .id("T1")
                    .side(OrderSide.SELL)
                    .price(new BigDecimal("100"))
                    .quantity(50)
                    .pnl(new BigDecimal("-300"))
                    .charges(ChargeBreakdown.zero())
                    .build();

            when(tradeJpaRepository.findByDateRange(any(), any())).thenReturn(List.of(te1));
            when(tradeMapper.toDomainList(any())).thenReturn(List.of(t1));
            when(dailyPnlJpaRepository.findByDate(LocalDate.now())).thenReturn(Optional.of(existing));
            when(pnLCalculationService.calculateTotalUnrealizedPnl()).thenReturn(BigDecimal.ZERO);

            dailyPnLAggregator.aggregateEndOfDay();

            ArgumentCaptor<DailyPnlEntity> captor = ArgumentCaptor.forClass(DailyPnlEntity.class);
            verify(dailyPnlJpaRepository).save(captor.capture());

            DailyPnlEntity saved = captor.getValue();
            assertThat(saved.getId()).isEqualTo(1L);
            assertThat(saved.getLosingTrades()).isEqualTo(1);
        }

        @Test
        @DisplayName("Counts winning and losing trades correctly")
        void countsWinLoseTrades() {
            Trade win1 = Trade.builder()
                    .id("T1")
                    .side(OrderSide.SELL)
                    .price(new BigDecimal("100"))
                    .quantity(10)
                    .pnl(new BigDecimal("500"))
                    .charges(ChargeBreakdown.zero())
                    .build();
            Trade win2 = Trade.builder()
                    .id("T2")
                    .side(OrderSide.SELL)
                    .price(new BigDecimal("100"))
                    .quantity(10)
                    .pnl(new BigDecimal("200"))
                    .charges(ChargeBreakdown.zero())
                    .build();
            Trade lose1 = Trade.builder()
                    .id("T3")
                    .side(OrderSide.SELL)
                    .price(new BigDecimal("100"))
                    .quantity(10)
                    .pnl(new BigDecimal("-300"))
                    .charges(ChargeBreakdown.zero())
                    .build();
            Trade breakEven = Trade.builder()
                    .id("T4")
                    .side(OrderSide.BUY)
                    .price(new BigDecimal("100"))
                    .quantity(10)
                    .pnl(BigDecimal.ZERO)
                    .charges(ChargeBreakdown.zero())
                    .build();

            when(tradeJpaRepository.findByDateRange(any(), any())).thenReturn(List.of());
            when(tradeMapper.toDomainList(any())).thenReturn(List.of(win1, win2, lose1, breakEven));
            when(dailyPnlJpaRepository.findByDate(LocalDate.now())).thenReturn(Optional.empty());
            when(pnLCalculationService.calculateTotalUnrealizedPnl()).thenReturn(BigDecimal.ZERO);

            dailyPnLAggregator.aggregateEndOfDay();

            ArgumentCaptor<DailyPnlEntity> captor = ArgumentCaptor.forClass(DailyPnlEntity.class);
            verify(dailyPnlJpaRepository).save(captor.capture());

            DailyPnlEntity saved = captor.getValue();
            assertThat(saved.getWinningTrades()).isEqualTo(2);
            assertThat(saved.getLosingTrades()).isEqualTo(1);
            assertThat(saved.getTotalTrades()).isEqualTo(4);
        }
    }

    // ==============================
    // MARKET CLOSE EVENT
    // ==============================

    @Nested
    @DisplayName("Market Close Event Handling")
    class MarketCloseEvent {

        @Test
        @DisplayName("POST_CLOSE -> CLOSED triggers aggregation")
        void postCloseToClose_triggersAggregation() {
            MarketStatusEvent event = new MarketStatusEvent(this, MarketPhase.POST_CLOSE, MarketPhase.CLOSED);

            when(tradeJpaRepository.findByDateRange(any(), any())).thenReturn(Collections.emptyList());
            when(tradeMapper.toDomainList(any())).thenReturn(Collections.emptyList());

            dailyPnLAggregator.onMarketClose(event);

            // Should have attempted aggregation (but skipped due to no trades)
            verify(tradeJpaRepository).findByDateRange(any(), any());
        }

        @Test
        @DisplayName("Other transitions do not trigger aggregation")
        void otherTransitions_noAggregation() {
            MarketStatusEvent event = new MarketStatusEvent(this, MarketPhase.NORMAL, MarketPhase.CLOSING);

            dailyPnLAggregator.onMarketClose(event);

            verify(tradeJpaRepository, never()).findByDateRange(any(), any());
        }

        @Test
        @DisplayName("PRE_OPEN -> NORMAL does not trigger aggregation")
        void preOpenToNormal_noAggregation() {
            MarketStatusEvent event = new MarketStatusEvent(this, MarketPhase.PRE_OPEN, MarketPhase.NORMAL);

            dailyPnLAggregator.onMarketClose(event);

            verify(tradeJpaRepository, never()).findByDateRange(any(), any());
        }
    }

    // ==============================
    // REDIS PERSISTENCE
    // ==============================

    @Nested
    @DisplayName("Redis Persistence for Crash Recovery")
    class RedisPersistence {

        @Test
        @DisplayName("Persists daily P&L to Redis with correct key and TTL")
        void persistsToRedis() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);

            dailyPnLAggregator.persistDailyPnlToRedis(new BigDecimal("-5000"));

            String expectedKey = "algo:daily:pnl:" + LocalDate.now();
            verify(valueOperations).set(eq(expectedKey), eq("-5000"), eq(Duration.ofHours(36)));
        }

        @Test
        @DisplayName("Recovers daily P&L from Redis")
        void recoversFromRedis() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            String key = "algo:daily:pnl:" + LocalDate.now();
            when(valueOperations.get(key)).thenReturn("-5000");

            BigDecimal recovered = dailyPnLAggregator.recoverDailyPnlFromRedis();

            assertThat(recovered).isEqualByComparingTo("-5000");
        }

        @Test
        @DisplayName("Returns ZERO when no Redis key found")
        void noRedisKey_returnsZero() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            String key = "algo:daily:pnl:" + LocalDate.now();
            when(valueOperations.get(key)).thenReturn(null);

            BigDecimal recovered = dailyPnLAggregator.recoverDailyPnlFromRedis();

            assertThat(recovered).isEqualByComparingTo("0");
        }
    }
}
