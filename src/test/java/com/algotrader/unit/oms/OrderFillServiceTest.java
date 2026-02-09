package com.algotrader.unit.oms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.model.Order;
import com.algotrader.domain.model.OrderFill;
import com.algotrader.entity.OrderFillEntity;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.event.OrderEvent;
import com.algotrader.event.OrderEventType;
import com.algotrader.mapper.OrderFillMapper;
import com.algotrader.oms.OrderFillService;
import com.algotrader.repository.jpa.OrderFillJpaRepository;
import com.algotrader.repository.redis.OrderRedisRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OrderFillService covering fill record creation,
 * VWAP calculation, position impact determination, and incremental fill processing.
 */
class OrderFillServiceTest {

    private OrderFillJpaRepository orderFillJpaRepository;
    private OrderFillMapper orderFillMapper;
    private OrderRedisRepository orderRedisRepository;
    private EventPublisherHelper eventPublisherHelper;
    private OrderFillService orderFillService;

    @BeforeEach
    void setUp() {
        orderFillJpaRepository = mock(OrderFillJpaRepository.class);
        orderFillMapper = mock(OrderFillMapper.class);
        orderRedisRepository = mock(OrderRedisRepository.class);
        eventPublisherHelper = mock(EventPublisherHelper.class);

        orderFillService = new OrderFillService(
                orderFillJpaRepository, orderFillMapper, orderRedisRepository, eventPublisherHelper);

        // Default: no previous fills
        when(orderFillJpaRepository.findByOrderId(any())).thenReturn(Collections.emptyList());
    }

    private Order sampleOrder(int filledQuantity, BigDecimal avgPrice) {
        return Order.builder()
                .id("ORD-1")
                .brokerOrderId("KT-12345")
                .instrumentToken(256265L)
                .tradingSymbol("NIFTY24FEB22000CE")
                .exchange("NFO")
                .side(OrderSide.BUY)
                .quantity(100)
                .filledQuantity(filledQuantity)
                .averageFillPrice(avgPrice)
                .status(filledQuantity == 100 ? OrderStatus.COMPLETE : OrderStatus.PARTIAL)
                .strategyId("STR1")
                .correlationId("COR-001")
                .placedAt(LocalDateTime.now().minusSeconds(5))
                .build();
    }

    @Nested
    @DisplayName("Fill Record Creation")
    class FillRecordCreation {

        @Test
        @DisplayName("Creates fill record with incremental quantity for first fill")
        void createsFillRecordForFirstFill() {
            Order order = sampleOrder(50, BigDecimal.valueOf(150.00));

            OrderFill fill = orderFillService.createFillRecord(order);

            assertThat(fill.getId()).isNotNull();
            assertThat(fill.getOrderId()).isEqualTo("KT-12345");
            assertThat(fill.getInstrumentToken()).isEqualTo(256265L);
            assertThat(fill.getTradingSymbol()).isEqualTo("NIFTY24FEB22000CE");
            assertThat(fill.getQuantity()).isEqualTo(50);
            assertThat(fill.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(150.00));
            assertThat(fill.getFilledAt()).isNotNull();
        }

        @Test
        @DisplayName("Calculates incremental quantity based on previous fills")
        void calculatesIncrementalQuantity() {
            // Previous fills already totalled 30
            OrderFillEntity previousFill = OrderFillEntity.builder()
                    .id("prev-1")
                    .orderId("KT-12345")
                    .quantity(30)
                    .build();
            when(orderFillJpaRepository.findByOrderId("KT-12345")).thenReturn(List.of(previousFill));

            Order order = sampleOrder(50, BigDecimal.valueOf(155.00));

            OrderFill fill = orderFillService.createFillRecord(order);

            // 50 total - 30 previous = 20 incremental
            assertThat(fill.getQuantity()).isEqualTo(20);
        }

        @Test
        @DisplayName("Zero incremental quantity for duplicate fill event")
        void zeroIncrementalForDuplicate() {
            // Previous fills already match current total
            OrderFillEntity previousFill = OrderFillEntity.builder()
                    .id("prev-1")
                    .orderId("KT-12345")
                    .quantity(50)
                    .build();
            when(orderFillJpaRepository.findByOrderId("KT-12345")).thenReturn(List.of(previousFill));

            Order order = sampleOrder(50, BigDecimal.valueOf(150.00));

            OrderFill fill = orderFillService.createFillRecord(order);

            assertThat(fill.getQuantity()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("VWAP Calculation")
    class VwapCalculation {

        @Test
        @DisplayName("VWAP for single fill equals fill price")
        void singleFillVwap() {
            List<OrderFill> fills = List.of(OrderFill.builder()
                    .quantity(50)
                    .price(BigDecimal.valueOf(150.00))
                    .build());

            BigDecimal vwap = orderFillService.calculateVwap(fills);

            assertThat(vwap).isEqualByComparingTo(BigDecimal.valueOf(150.00));
        }

        @Test
        @DisplayName("VWAP correct for multiple fills at different prices")
        void multipleFillsVwap() {
            List<OrderFill> fills = List.of(
                    OrderFill.builder()
                            .quantity(30)
                            .price(BigDecimal.valueOf(100.00))
                            .build(),
                    OrderFill.builder()
                            .quantity(70)
                            .price(BigDecimal.valueOf(200.00))
                            .build());

            BigDecimal vwap = orderFillService.calculateVwap(fills);

            // VWAP = (30*100 + 70*200) / (30+70) = (3000 + 14000) / 100 = 170.00
            assertThat(vwap).isEqualByComparingTo(BigDecimal.valueOf(170.00));
        }

        @Test
        @DisplayName("VWAP returns zero for empty fill list")
        void emptyFillsVwap() {
            BigDecimal vwap = orderFillService.calculateVwap(Collections.emptyList());

            assertThat(vwap).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("VWAP returns zero for null fill list")
        void nullFillsVwap() {
            BigDecimal vwap = orderFillService.calculateVwap(null);

            assertThat(vwap).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("VWAP handles three fills with equal quantities")
        void threeFillsEqualQuantity() {
            List<OrderFill> fills = List.of(
                    OrderFill.builder()
                            .quantity(10)
                            .price(BigDecimal.valueOf(100.00))
                            .build(),
                    OrderFill.builder()
                            .quantity(10)
                            .price(BigDecimal.valueOf(200.00))
                            .build(),
                    OrderFill.builder()
                            .quantity(10)
                            .price(BigDecimal.valueOf(300.00))
                            .build());

            BigDecimal vwap = orderFillService.calculateVwap(fills);

            // VWAP = (10*100 + 10*200 + 10*300) / 30 = 6000 / 30 = 200.00
            assertThat(vwap).isEqualByComparingTo(BigDecimal.valueOf(200.00));
        }
    }

    @Nested
    @DisplayName("Position Impact")
    class PositionImpact {

        @Test
        @DisplayName("Position closed when current quantity is zero")
        void positionClosed() {
            String impact = orderFillService.determinePositionImpact(0, 50, OrderSide.SELL);
            assertThat(impact).isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("Position opened when previous quantity is zero")
        void positionOpened() {
            String impact = orderFillService.determinePositionImpact(50, 0, OrderSide.BUY);
            assertThat(impact).isEqualTo("OPENED");
        }

        @Test
        @DisplayName("Position increased when absolute quantity grows")
        void positionIncreased() {
            String impact = orderFillService.determinePositionImpact(100, 50, OrderSide.BUY);
            assertThat(impact).isEqualTo("INCREASED");
        }

        @Test
        @DisplayName("Position reduced when absolute quantity shrinks")
        void positionReduced() {
            String impact = orderFillService.determinePositionImpact(30, 50, OrderSide.SELL);
            assertThat(impact).isEqualTo("REDUCED");
        }

        @Test
        @DisplayName("Short position increased")
        void shortPositionIncreased() {
            // -100 is bigger (abs) than -50
            String impact = orderFillService.determinePositionImpact(-100, -50, OrderSide.SELL);
            assertThat(impact).isEqualTo("INCREASED");
        }

        @Test
        @DisplayName("Short position reduced")
        void shortPositionReduced() {
            // -30 is smaller (abs) than -50
            String impact = orderFillService.determinePositionImpact(-30, -50, OrderSide.BUY);
            assertThat(impact).isEqualTo("REDUCED");
        }
    }

    @Nested
    @DisplayName("Fill Event Processing")
    class FillEventProcessing {

        @Test
        @DisplayName("Partial fill event persists fill and updates order in Redis")
        void partialFillPersistsAndUpdates() {
            Order order = sampleOrder(50, BigDecimal.valueOf(150.00));
            OrderEvent event = new OrderEvent(this, order, OrderEventType.PARTIALLY_FILLED, OrderStatus.OPEN);

            // Mock mapper to return entity
            when(orderFillMapper.toEntity(any()))
                    .thenReturn(OrderFillEntity.builder().build());
            when(orderFillMapper.toDomainList(any()))
                    .thenReturn(List.of(OrderFill.builder()
                            .quantity(50)
                            .price(BigDecimal.valueOf(150.00))
                            .build()));

            orderFillService.onOrderFill(event);

            verify(orderFillJpaRepository).save(any(OrderFillEntity.class));
            verify(orderRedisRepository).save(order);
        }

        @Test
        @DisplayName("Full fill event persists fill and updates order in Redis")
        void fullFillPersistsAndUpdates() {
            Order order = sampleOrder(100, BigDecimal.valueOf(155.00));
            OrderEvent event = new OrderEvent(this, order, OrderEventType.FILLED, OrderStatus.PARTIAL);

            when(orderFillMapper.toEntity(any()))
                    .thenReturn(OrderFillEntity.builder().build());
            when(orderFillMapper.toDomainList(any()))
                    .thenReturn(List.of(OrderFill.builder()
                            .quantity(100)
                            .price(BigDecimal.valueOf(155.00))
                            .build()));

            orderFillService.onOrderFill(event);

            verify(orderFillJpaRepository).save(any(OrderFillEntity.class));
            verify(orderRedisRepository).save(order);
        }
    }
}
