package com.algotrader.broker.mapper;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.enums.OrderType;
import com.algotrader.domain.model.Order;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.OrderParams;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Maps between Kite SDK order objects and our domain {@link Order} model.
 *
 * <p>Kite SDK uses public fields (not getters/setters) and stores most numeric
 * values as Strings (orderId, quantity, price, filledQuantity, etc.), so MapStruct
 * cannot auto-generate these mappings. All conversions are manual and null-safe.
 *
 * <p>Mapping directions:
 * <ul>
 *   <li>{@code toDomain} — Kite {@code com.zerodhatech.models.Order} -> domain {@link Order}</li>
 *   <li>{@code toOrderParams} — domain {@link Order} -> Kite {@link OrderParams} (for placement)</li>
 * </ul>
 */
@Component
public class KiteOrderMapper {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Converts a Kite SDK Order (from getOrders/getOrderHistory/placeOrder response)
     * to our domain Order model.
     *
     * <p>Kite stores quantity, price, and filledQuantity as Strings — we parse them
     * to int/BigDecimal. Null or empty strings become 0/ZERO.
     */
    public Order toDomain(com.zerodhatech.models.Order kiteOrder) {
        if (kiteOrder == null) {
            return null;
        }

        return Order.builder()
                .brokerOrderId(kiteOrder.orderId)
                .instrumentToken(parseLong(kiteOrder.exchange + ":" + kiteOrder.tradingSymbol))
                .tradingSymbol(kiteOrder.tradingSymbol)
                .exchange(kiteOrder.exchange)
                .side(mapTransactionType(kiteOrder.transactionType))
                .type(mapOrderType(kiteOrder.orderType))
                .product(kiteOrder.product)
                .quantity(parseInt(kiteOrder.quantity))
                .price(parseBigDecimal(kiteOrder.price))
                .triggerPrice(parseBigDecimal(kiteOrder.triggerPrice))
                .status(mapStatus(kiteOrder.status))
                .filledQuantity(parseInt(kiteOrder.filledQuantity))
                .averageFillPrice(parseBigDecimal(kiteOrder.averagePrice))
                .parentOrderId(kiteOrder.parentOrderId)
                .rejectionReason(kiteOrder.statusMessage)
                .placedAt(toLocalDateTime(kiteOrder.orderTimestamp))
                .updatedAt(toLocalDateTime(kiteOrder.exchangeTimestamp))
                .build();
    }

    /**
     * Converts a list of Kite SDK Orders to domain Orders.
     */
    public List<Order> toDomainList(List<com.zerodhatech.models.Order> kiteOrders) {
        if (kiteOrders == null) {
            return List.of();
        }
        return kiteOrders.stream().map(this::toDomain).toList();
    }

    /**
     * Builds Kite {@link OrderParams} from a domain Order for placement via the Kite API.
     *
     * <p>Sets exchange to "NFO" by default for F&O; uses the Order's exchange field if present.
     * Product defaults to "NRML" (overnight F&O) if not specified on the Order.
     */
    public OrderParams toOrderParams(Order order) {
        OrderParams params = new OrderParams();
        params.tradingsymbol = order.getTradingSymbol();
        params.exchange = order.getExchange() != null ? order.getExchange() : Constants.EXCHANGE_NFO;
        params.transactionType =
                order.getSide() == OrderSide.BUY ? Constants.TRANSACTION_TYPE_BUY : Constants.TRANSACTION_TYPE_SELL;
        params.orderType = mapToKiteOrderType(order.getType());
        params.quantity = order.getQuantity();
        params.product = order.getProduct() != null ? order.getProduct() : Constants.PRODUCT_NRML;
        params.validity = Constants.VALIDITY_DAY;

        // Price for LIMIT and SL orders
        if (order.getPrice() != null && (order.getType() == OrderType.LIMIT || order.getType() == OrderType.SL)) {
            params.price = order.getPrice().doubleValue();
        }

        // Trigger price for SL and SL_M orders
        if (order.getTriggerPrice() != null && (order.getType() == OrderType.SL || order.getType() == OrderType.SL_M)) {
            params.triggerPrice = order.getTriggerPrice().doubleValue();
        }

        return params;
    }

    // ---- Enum mapping helpers ----

    OrderSide mapTransactionType(String transactionType) {
        if (transactionType == null) {
            return null;
        }
        return switch (transactionType) {
            case "BUY" -> OrderSide.BUY;
            case "SELL" -> OrderSide.SELL;
            default -> null;
        };
    }

    OrderType mapOrderType(String kiteOrderType) {
        if (kiteOrderType == null) {
            return null;
        }
        return switch (kiteOrderType) {
            case "MARKET" -> OrderType.MARKET;
            case "LIMIT" -> OrderType.LIMIT;
            case "SL" -> OrderType.SL;
            case "SL-M" -> OrderType.SL_M;
            default -> null;
        };
    }

    /**
     * Maps Kite order status string to our OrderStatus enum.
     *
     * <p>Kite uses: OPEN, COMPLETE, CANCELLED, REJECTED, TRIGGER PENDING, UPDATE, PUT ORDER REQ RECEIVED.
     * We treat UPDATE and PUT ORDER REQ RECEIVED as OPEN (order is active).
     */
    OrderStatus mapStatus(String kiteStatus) {
        if (kiteStatus == null) {
            return OrderStatus.PENDING;
        }
        return switch (kiteStatus) {
            case "OPEN", "UPDATE", "PUT ORDER REQ RECEIVED" -> OrderStatus.OPEN;
            case "COMPLETE" -> OrderStatus.COMPLETE;
            case "CANCELLED" -> OrderStatus.CANCELLED;
            case "REJECTED" -> OrderStatus.REJECTED;
            case "TRIGGER PENDING" -> OrderStatus.TRIGGER_PENDING;
            default -> OrderStatus.OPEN;
        };
    }

    String mapToKiteOrderType(OrderType type) {
        if (type == null) {
            return Constants.ORDER_TYPE_MARKET;
        }
        return switch (type) {
            case MARKET -> Constants.ORDER_TYPE_MARKET;
            case LIMIT -> Constants.ORDER_TYPE_LIMIT;
            case SL -> Constants.ORDER_TYPE_SL;
            case SL_M -> Constants.ORDER_TYPE_SLM;
        };
    }

    // ---- Parsing helpers ----

    private int parseInt(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // instrumentToken from Kite may be in "exchange:symbol" format — not a number
            return 0L;
        }
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(IST).toLocalDateTime();
    }
}
