package com.algotrader.oms;

import com.algotrader.broker.BrokerGateway;
import com.algotrader.domain.enums.OrderStatus;
import com.algotrader.domain.model.Order;
import com.algotrader.event.EventPublisherHelper;
import com.algotrader.repository.redis.OrderRedisRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Dedicated consumer thread that drains the {@link OrderQueue} and executes orders
 * via the {@link BrokerGateway}.
 *
 * <p>Uses {@code OrderQueue.take()} which blocks until an order is available, providing
 * zero-latency order processing without the CPU waste and 10ms delay of polling.
 * The consumer thread is started on application startup via {@link #start()} and stopped
 * gracefully during shutdown via {@link #stop()}.
 *
 * <p>Each processed order publishes an appropriate {@link com.algotrader.event.OrderEvent}
 * (PLACED on success, REJECTED on failure).
 *
 * <p>WebSocket order submission channel is planned for Task 8.2:
 * #TODO Task 8.2 -- Add /app/orders/place STOMP destination for frontend order submission
 */
@Component
public class OrderQueueProcessor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OrderQueueProcessor.class);

    private final OrderQueue orderQueue;
    private final BrokerGateway brokerGateway;
    private final EventPublisherHelper eventPublisherHelper;
    private final IdempotencyService idempotencyService;
    private final OrderRedisRepository orderRedisRepository;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread consumerThread;

    public OrderQueueProcessor(
            OrderQueue orderQueue,
            BrokerGateway brokerGateway,
            EventPublisherHelper eventPublisherHelper,
            IdempotencyService idempotencyService,
            OrderRedisRepository orderRedisRepository) {
        this.orderQueue = orderQueue;
        this.brokerGateway = brokerGateway;
        this.eventPublisherHelper = eventPublisherHelper;
        this.idempotencyService = idempotencyService;
        this.orderRedisRepository = orderRedisRepository;
    }

    /**
     * Starts the consumer thread. Called on application startup via SmartLifecycle.
     * The thread loops on {@code orderQueue.take()} until {@link #stop()} is called.
     */
    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            consumerThread = new Thread(this::processLoop, "order-queue-processor");
            consumerThread.setDaemon(true);
            consumerThread.start();
            log.info("OrderQueueProcessor started");
        }
    }

    /**
     * Stops the consumer thread gracefully. Any in-flight order completes before
     * the thread exits. Called during graceful shutdown via SmartLifecycle.
     */
    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (consumerThread != null) {
                consumerThread.interrupt();
            }
            log.info("OrderQueueProcessor stopping");
        }
    }

    /** Returns whether the processor is currently running. */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // Start early so the queue is draining before strategies begin evaluating
        return 0;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * Main processing loop. Blocks on {@code orderQueue.take()} until an order
     * is available, then processes it. Continues until {@link #stop()} sets
     * running to false and interrupts the thread.
     */
    private void processLoop() {
        while (running.get()) {
            try {
                PrioritizedOrder prioritizedOrder = orderQueue.take();
                processOrder(prioritizedOrder);
            } catch (InterruptedException e) {
                if (!running.get()) {
                    log.info("OrderQueueProcessor interrupted during shutdown");
                    Thread.currentThread().interrupt();
                    break;
                }
                log.warn("OrderQueueProcessor interrupted unexpectedly, resuming");
                Thread.currentThread().interrupt();
            }
        }

        // Drain remaining orders on shutdown
        drainRemaining();
    }

    /**
     * Processes a single order: converts to domain model, places via broker,
     * publishes events, and marks idempotency key.
     */
    public void processOrder(PrioritizedOrder prioritizedOrder) {
        OrderRequest orderRequest = prioritizedOrder.getOrderRequest();
        long queueLatency = System.currentTimeMillis() - prioritizedOrder.getEnqueuedAt();

        log.debug(
                "Processing order: priority={}, symbol={}, queueLatency={}ms",
                prioritizedOrder.getPriority(),
                orderRequest.getTradingSymbol(),
                queueLatency);

        Order order = toOrder(orderRequest);

        try {
            String brokerOrderId = brokerGateway.placeOrder(order);
            order.setBrokerOrderId(brokerOrderId);
            order.setStatus(OrderStatus.OPEN);
            order.setPlacedAt(LocalDateTime.now());

            // Mark idempotency key after successful placement
            idempotencyService.markProcessed(orderRequest);

            // Persist to Redis BEFORE publishing event so downstream handlers
            // (e.g., KiteOrderUpdateHandler) can look up the order by brokerOrderId
            orderRedisRepository.save(order);

            eventPublisherHelper.publishOrderPlaced(this, order);

            log.info(
                    "Order placed: brokerOrderId={}, priority={}, symbol={}, queueLatency={}ms",
                    brokerOrderId,
                    prioritizedOrder.getPriority(),
                    orderRequest.getTradingSymbol(),
                    queueLatency);

        } catch (Exception e) {
            log.error(
                    "Order placement failed: symbol={}, priority={}",
                    orderRequest.getTradingSymbol(),
                    prioritizedOrder.getPriority(),
                    e);

            order.setStatus(OrderStatus.REJECTED);
            order.setRejectionReason(e.getMessage());
            eventPublisherHelper.publishOrderRejected(this, order);
        }
    }

    /**
     * Drains any remaining orders from the queue during shutdown.
     * Processes them synchronously to avoid losing orders.
     */
    private void drainRemaining() {
        int drained = 0;
        PrioritizedOrder remaining;
        while ((remaining = orderQueue.poll()) != null) {
            processOrder(remaining);
            drained++;
        }
        if (drained > 0) {
            log.info("Drained {} remaining orders during shutdown", drained);
        }
    }

    /**
     * Converts an {@link OrderRequest} into an {@link Order} domain model.
     */
    private Order toOrder(OrderRequest orderRequest) {
        return Order.builder()
                .id(UUID.randomUUID().toString())
                .instrumentToken(orderRequest.getInstrumentToken())
                .tradingSymbol(orderRequest.getTradingSymbol())
                .exchange(orderRequest.getExchange())
                .side(orderRequest.getSide())
                .type(orderRequest.getType())
                .product(orderRequest.getProduct())
                .quantity(orderRequest.getQuantity())
                .price(orderRequest.getPrice())
                .triggerPrice(orderRequest.getTriggerPrice())
                .strategyId(orderRequest.getStrategyId())
                .correlationId(orderRequest.getCorrelationId())
                .status(OrderStatus.PENDING)
                .build();
    }
}
