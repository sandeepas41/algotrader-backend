package com.algotrader.repository.jpa;

import com.algotrader.entity.OrderFillEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the order_fills table.
 * Tracks partial fills within an order (1 order → N fills).
 * Used by the fill aggregation logic to compute average fill price and total filled quantity.
 */
@Repository
public interface OrderFillJpaRepository extends JpaRepository<OrderFillEntity, String> {

    List<OrderFillEntity> findByOrderId(String orderId);

    List<OrderFillEntity> findByExchangeOrderId(String exchangeOrderId);
}
