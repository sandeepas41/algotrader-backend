package com.algotrader.unit.broker;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.broker.mapper.KiteOrderMapper;
import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.zerodhatech.models.OrderParams;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KiteOrderMapper}.
 *
 * <p>Verifies Kite SDK Order (with String fields) -> domain Order mapping,
 * domain Order -> Kite OrderParams mapping, and all enum conversions.
 */
class KiteOrderMapperTest {

    private KiteOrderMapper kiteOrderMapper;

    @BeforeEach
    void setUp() {
        kiteOrderMapper = new KiteOrderMapper();
    }

    @Nested
    @DisplayName("Kite Order -> Domain Order (toDomain)")
    class ToDomainTests {

        @Test
        @DisplayName("maps all fields from a completed Kite order")
        void mapsCompletedKiteOrder() {
            com.zerodhatech.models.Order kiteOrder = new com.zerodhatech.models.Order();
            kiteOrder.orderId = "220101000001234";
            kiteOrder.tradingSymbol = "NIFTY24FEB22000CE";
            kiteOrder.exchange = "NFO";
            kiteOrder.transactionType = "BUY";
            kiteOrder.orderType = "LIMIT";
            kiteOrder.product = "NRML";
            kiteOrder.quantity = "50";
            kiteOrder.price = "150.25";
            kiteOrder.triggerPrice = "0";
            kiteOrder.status = "COMPLETE";
            kiteOrder.filledQuantity = "50";
            kiteOrder.averagePrice = "150.10";
            kiteOrder.parentOrderId = null;
            kiteOrder.statusMessage = null;
            kiteOrder.orderTimestamp = new Date();
            kiteOrder.exchangeTimestamp = new Date();
            kiteOrder.tag = "SC01E0001";

            Order order = kiteOrderMapper.toDomain(kiteOrder);

            assertThat(order.getBrokerOrderId()).isEqualTo("220101000001234");
            assertThat(order.getTradingSymbol()).isEqualTo("NIFTY24FEB22000CE");
            assertThat(order.getExchange()).isEqualTo("NFO");
            assertThat(order.getSide()).isEqualTo(OrderSide.BUY);
            assertThat(order.getType()).isEqualTo(OrderType.LIMIT);
            assertThat(order.getProduct()).isEqualTo("NRML");
            assertThat(order.getQuantity()).isEqualTo(50);
            assertThat(order.getPrice()).isEqualByComparingTo(new BigDecimal("150.25"));
            assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETE);
            assertThat(order.getFilledQuantity()).isEqualTo(50);
            assertThat(order.getAverageFillPrice()).isEqualByComparingTo(new BigDecimal("150.10"));
            assertThat(order.getPlacedAt()).isNotNull();
        }

        @Test
        @DisplayName("handles null Kite order gracefully")
        void handlesNullKiteOrder() {
            assertThat(kiteOrderMapper.toDomain(null)).isNull();
        }

        @Test
        @DisplayName("handles empty/null String fields without NPE")
        void handlesEmptyStringFields() {
            com.zerodhatech.models.Order kiteOrder = new com.zerodhatech.models.Order();
            kiteOrder.orderId = "12345";
            kiteOrder.quantity = null;
            kiteOrder.price = "";
            kiteOrder.filledQuantity = null;
            kiteOrder.averagePrice = null;
            kiteOrder.status = null;

            Order order = kiteOrderMapper.toDomain(kiteOrder);

            assertThat(order.getQuantity()).isEqualTo(0);
            assertThat(order.getPrice()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(order.getFilledQuantity()).isEqualTo(0);
            assertThat(order.getAverageFillPrice()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        }

        @Test
        @DisplayName("maps SELL transaction type correctly")
        void mapsSellTransactionType() {
            com.zerodhatech.models.Order kiteOrder = new com.zerodhatech.models.Order();
            kiteOrder.orderId = "12345";
            kiteOrder.transactionType = "SELL";

            Order order = kiteOrderMapper.toDomain(kiteOrder);

            assertThat(order.getSide()).isEqualTo(OrderSide.SELL);
        }

        @Test
        @DisplayName("maps SL-M order type correctly")
        void mapsSlmOrderType() {
            com.zerodhatech.models.Order kiteOrder = new com.zerodhatech.models.Order();
            kiteOrder.orderId = "12345";
            kiteOrder.orderType = "SL-M";

            Order order = kiteOrderMapper.toDomain(kiteOrder);

            assertThat(order.getType()).isEqualTo(OrderType.SL_M);
        }

        @Test
        @DisplayName("converts list of Kite orders")
        void convertsList() {
            com.zerodhatech.models.Order o1 = new com.zerodhatech.models.Order();
            o1.orderId = "1";
            o1.transactionType = "BUY";
            com.zerodhatech.models.Order o2 = new com.zerodhatech.models.Order();
            o2.orderId = "2";
            o2.transactionType = "SELL";

            List<Order> orders = kiteOrderMapper.toDomainList(List.of(o1, o2));

            assertThat(orders).hasSize(2);
            assertThat(orders.get(0).getSide()).isEqualTo(OrderSide.BUY);
            assertThat(orders.get(1).getSide()).isEqualTo(OrderSide.SELL);
        }

        @Test
        @DisplayName("null list returns empty list")
        void nullListReturnsEmpty() {
            assertThat(kiteOrderMapper.toDomainList(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Domain Order -> Kite OrderParams (toOrderParams)")
    class ToOrderParamsTests {

        @Test
        @DisplayName("builds OrderParams for a LIMIT BUY order")
        void buildsLimitBuyOrder() {
            Order order = Order.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .exchange("NFO")
                    .side(OrderSide.BUY)
                    .type(OrderType.LIMIT)
                    .quantity(50)
                    .price(new BigDecimal("150.25"))
                    .product("NRML")
                    .build();

            OrderParams params = kiteOrderMapper.toOrderParams(order);

            assertThat(params.tradingsymbol).isEqualTo("NIFTY24FEB22000CE");
            assertThat(params.exchange).isEqualTo("NFO");
            assertThat(params.transactionType).isEqualTo("BUY");
            assertThat(params.orderType).isEqualTo("LIMIT");
            assertThat(params.quantity).isEqualTo(50);
            assertThat(params.price).isEqualTo(150.25);
            assertThat(params.product).isEqualTo("NRML");
            assertThat(params.validity).isEqualTo("DAY");
        }

        @Test
        @DisplayName("builds OrderParams for a MARKET SELL order")
        void buildsMarketSellOrder() {
            Order order = Order.builder()
                    .tradingSymbol("NIFTY24FEB22000PE")
                    .exchange("NFO")
                    .side(OrderSide.SELL)
                    .type(OrderType.MARKET)
                    .quantity(75)
                    .build();

            OrderParams params = kiteOrderMapper.toOrderParams(order);

            assertThat(params.transactionType).isEqualTo("SELL");
            assertThat(params.orderType).isEqualTo("MARKET");
            assertThat(params.quantity).isEqualTo(75);
            // MARKET orders should not have price set
            assertThat(params.price).isNull();
        }

        @Test
        @DisplayName("builds OrderParams for an SL order with price and trigger price")
        void buildsSlOrder() {
            Order order = Order.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .exchange("NFO")
                    .side(OrderSide.SELL)
                    .type(OrderType.SL)
                    .quantity(50)
                    .price(new BigDecimal("140.00"))
                    .triggerPrice(new BigDecimal("142.00"))
                    .build();

            OrderParams params = kiteOrderMapper.toOrderParams(order);

            assertThat(params.orderType).isEqualTo("SL");
            assertThat(params.price).isEqualTo(140.00);
            assertThat(params.triggerPrice).isEqualTo(142.00);
        }

        @Test
        @DisplayName("builds OrderParams for SL_M with trigger only (no price)")
        void buildsSlmOrder() {
            Order order = Order.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .exchange("NFO")
                    .side(OrderSide.BUY)
                    .type(OrderType.SL_M)
                    .quantity(50)
                    .triggerPrice(new BigDecimal("155.00"))
                    .build();

            OrderParams params = kiteOrderMapper.toOrderParams(order);

            assertThat(params.orderType).isEqualTo("SL-M");
            assertThat(params.triggerPrice).isEqualTo(155.00);
            // SL_M should not have limit price
            assertThat(params.price).isNull();
        }

        @Test
        @DisplayName("defaults exchange to NFO and product to NRML when not specified")
        void defaultsExchangeAndProduct() {
            Order order = Order.builder()
                    .tradingSymbol("NIFTY24FEB22000CE")
                    .side(OrderSide.BUY)
                    .type(OrderType.MARKET)
                    .quantity(50)
                    .build();

            OrderParams params = kiteOrderMapper.toOrderParams(order);

            assertThat(params.exchange).isEqualTo("NFO");
            assertThat(params.product).isEqualTo("NRML");
        }
    }

    @Nested
    @DisplayName("Status Mapping (via toDomain)")
    class StatusMappingTests {

        private Order mapWithStatus(String kiteStatus) {
            com.zerodhatech.models.Order kiteOrder = new com.zerodhatech.models.Order();
            kiteOrder.orderId = "12345";
            kiteOrder.status = kiteStatus;
            return kiteOrderMapper.toDomain(kiteOrder);
        }

        @Test
        @DisplayName("maps all Kite status strings correctly")
        void mapsAllStatuses() {
            assertThat(mapWithStatus("OPEN").getStatus()).isEqualTo(OrderStatus.OPEN);
            assertThat(mapWithStatus("COMPLETE").getStatus()).isEqualTo(OrderStatus.COMPLETE);
            assertThat(mapWithStatus("CANCELLED").getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(mapWithStatus("REJECTED").getStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(mapWithStatus("TRIGGER PENDING").getStatus()).isEqualTo(OrderStatus.TRIGGER_PENDING);
            assertThat(mapWithStatus("UPDATE").getStatus()).isEqualTo(OrderStatus.OPEN);
            assertThat(mapWithStatus("PUT ORDER REQ RECEIVED").getStatus()).isEqualTo(OrderStatus.OPEN);
        }

        @Test
        @DisplayName("null status maps to PENDING")
        void nullStatusMapsToPending() {
            assertThat(mapWithStatus(null).getStatus()).isEqualTo(OrderStatus.PENDING);
        }

        @Test
        @DisplayName("unknown status defaults to OPEN")
        void unknownStatusDefaultsToOpen() {
            assertThat(mapWithStatus("SOME_UNKNOWN").getStatus()).isEqualTo(OrderStatus.OPEN);
        }
    }
}
