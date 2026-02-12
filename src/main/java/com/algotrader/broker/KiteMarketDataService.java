package com.algotrader.broker;

import com.algotrader.config.KiteConfig;
import com.algotrader.domain.model.DepthItem;
import com.algotrader.domain.model.Tick;
import com.algotrader.event.TickEvent;
import com.zerodhatech.models.Depth;
import com.zerodhatech.ticker.KiteTicker;
import com.zerodhatech.ticker.OnConnect;
import com.zerodhatech.ticker.OnDisconnect;
import com.zerodhatech.ticker.OnError;
import com.zerodhatech.ticker.OnOrderUpdate;
import com.zerodhatech.ticker.OnTicks;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Manages the Kite WebSocket ticker for real-time market data streaming.
 *
 * <p>Wraps the Kite SDK's {@link KiteTicker} to provide connection lifecycle management,
 * instrument subscription, tick-to-domain mapping, and reconnection with exponential backoff.
 *
 * <p>On each tick arrival, maps the Kite SDK Tick to our domain {@link Tick} model and
 * publishes a {@link TickEvent} via Spring's event system. Downstream listeners (position
 * updaters, strategy engines, Greeks calculators) process these events asynchronously.
 *
 * <p>Connection lifecycle:
 * <ul>
 *   <li>{@link #connect(String)} — creates a new KiteTicker and connects</li>
 *   <li>{@link #reconnectWithNewToken(String)} — disconnects old ticker, creates new one,
 *       resubscribes all instruments (called after re-authentication)</li>
 *   <li>{@link #disconnect()} — graceful shutdown</li>
 * </ul>
 *
 * <p>Reconnection backoff: 1s initial, doubling up to 30s max. After {@code MAX_RECONNECT_RETRIES}
 * consecutive failures, the service enters DEGRADED state and stops retrying.
 */
@Service
public class KiteMarketDataService {

    private static final Logger log = LoggerFactory.getLogger(KiteMarketDataService.class);

    /** Initial reconnection delay in milliseconds. */
    static final long INITIAL_RECONNECT_DELAY_MS = 1000;

    /** Maximum reconnection delay in milliseconds. */
    static final long MAX_RECONNECT_DELAY_MS = 30_000;

    /** Maximum reconnection attempts before entering DEGRADED state. */
    static final int MAX_RECONNECT_RETRIES = 10;

    private final KiteConfig kiteConfig;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final KiteOrderUpdateHandler kiteOrderUpdateHandler;

    /** Tracks all instrument tokens currently subscribed on the WebSocket. */
    private final Set<Long> subscribedTokens = ConcurrentHashMap.newKeySet();

    /** Mode (LTP/Quote/Full) per instrument token. Default is modeFull. */
    private final Map<Long, String> tokenModes = new ConcurrentHashMap<>();

    private volatile KiteTicker kiteTicker;
    private volatile boolean connected;
    private volatile boolean degraded;

    /** Consecutive reconnection failure count (reset on successful connect). */
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    public KiteMarketDataService(
            KiteConfig kiteConfig,
            ApplicationEventPublisher applicationEventPublisher,
            KiteOrderUpdateHandler kiteOrderUpdateHandler) {
        this.kiteConfig = kiteConfig;
        this.applicationEventPublisher = applicationEventPublisher;
        this.kiteOrderUpdateHandler = kiteOrderUpdateHandler;
    }

    /**
     * Creates a new KiteTicker and connects to the Kite WebSocket.
     *
     * @param accessToken valid Kite access token for authentication
     */
    public void connect(String accessToken) {
        if (connected) {
            log.warn("Ticker already connected, ignoring connect request");
            return;
        }

        log.info("Connecting to Kite WebSocket...");
        kiteTicker = createTicker(accessToken);
        setupCallbacks();
        kiteTicker.setTryReconnection(true);

        try {
            kiteTicker.setMaximumRetries(MAX_RECONNECT_RETRIES);
            kiteTicker.setMaximumRetryInterval(Math.toIntExact(MAX_RECONNECT_DELAY_MS / 1000));
        } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException e) {
            // KiteException extends Throwable (not Exception) — must catch explicitly
            log.warn("Failed to configure ticker retry settings: {}", e.message);
        }

        kiteTicker.connect();
    }

    /**
     * Reconnects the WebSocket ticker with a new access token.
     * Called after successful re-authentication (token refresh).
     *
     * <p>Disconnects the old ticker, creates a new one with the new token,
     * and resubscribes all previously active instruments.
     *
     * @param accessToken the new valid access token
     */
    public void reconnectWithNewToken(String accessToken) {
        log.info("Reconnecting ticker with new access token...");
        disconnect();

        // Reset reconnection state
        reconnectAttempts.set(0);
        degraded = false;

        connect(accessToken);

        // Resubscribe all instruments that were active before disconnect
        if (!subscribedTokens.isEmpty()) {
            resubscribe();
            log.info("Resubscribed {} instruments after reconnection", subscribedTokens.size());
        }
    }

    /**
     * Gracefully disconnects the WebSocket ticker.
     */
    public void disconnect() {
        if (kiteTicker != null) {
            try {
                kiteTicker.disconnect();
                log.info("Kite ticker disconnected");
            } catch (Exception e) {
                log.warn("Error disconnecting ticker: {}", e.getMessage());
            }
        }
        connected = false;
    }

    /**
     * Subscribes instrument tokens to the WebSocket in FULL mode (default).
     * If the ticker is not connected, tokens are queued and subscribed on connect.
     *
     * @param instrumentTokens tokens to subscribe
     */
    public void subscribe(List<Long> instrumentTokens) {
        subscribedTokens.addAll(instrumentTokens);
        instrumentTokens.forEach(t -> tokenModes.put(t, KiteTicker.modeFull));

        if (kiteTicker != null && connected) {
            ArrayList<Long> tokens = new ArrayList<>(instrumentTokens);
            kiteTicker.subscribe(tokens);
            kiteTicker.setMode(tokens, KiteTicker.modeFull);
            log.info("Subscribed {} instruments (total: {})", instrumentTokens.size(), subscribedTokens.size());
        }
    }

    /**
     * Unsubscribes instrument tokens from the WebSocket.
     *
     * @param instrumentTokens tokens to unsubscribe
     */
    public void unsubscribe(List<Long> instrumentTokens) {
        subscribedTokens.removeAll(instrumentTokens);
        instrumentTokens.forEach(tokenModes::remove);

        if (kiteTicker != null && connected) {
            kiteTicker.unsubscribe(new ArrayList<>(instrumentTokens));
            log.info("Unsubscribed {} instruments (total: {})", instrumentTokens.size(), subscribedTokens.size());
        }
    }

    /**
     * Changes the tick mode for specific instruments.
     *
     * @param instrumentTokens tokens to change mode for
     * @param mode one of KiteTicker.modeLTP, KiteTicker.modeQuote, KiteTicker.modeFull
     */
    public void setMode(List<Long> instrumentTokens, String mode) {
        instrumentTokens.forEach(t -> tokenModes.put(t, mode));
        if (kiteTicker != null && connected) {
            kiteTicker.setMode(new ArrayList<>(instrumentTokens), mode);
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public int getSubscribedCount() {
        return subscribedTokens.size();
    }

    public Set<Long> getSubscribedTokens() {
        return Collections.unmodifiableSet(subscribedTokens);
    }

    // ---- Private helpers ----

    /** Creates a new KiteTicker instance (extracted for testability). */
    KiteTicker createTicker(String accessToken) {
        // KiteTicker constructor order: (accessToken, apiKey)
        return new KiteTicker(accessToken, kiteConfig.getApiKey());
    }

    private void setupCallbacks() {
        kiteTicker.setOnConnectedListener(new OnConnect() {
            @Override
            public void onConnected() {
                onConnect();
            }
        });

        kiteTicker.setOnDisconnectedListener(new OnDisconnect() {
            @Override
            public void onDisconnected() {
                onDisconnect();
            }
        });

        kiteTicker.setOnTickerArrivalListener(new OnTicks() {
            @Override
            public void onTicks(ArrayList<com.zerodhatech.models.Tick> ticks) {
                KiteMarketDataService.this.onTicks(ticks);
            }
        });

        kiteTicker.setOnErrorListener(new OnError() {
            @Override
            public void onError(Exception exception) {
                log.error("Kite ticker error: {}", exception.getMessage());
            }

            @Override
            public void onError(com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException kiteException) {
                log.error("Kite ticker KiteException: {}", kiteException.message);
            }

            @Override
            public void onError(String error) {
                log.error("Kite ticker error: {}", error);
            }
        });

        kiteTicker.setOnOrderUpdateListener(new OnOrderUpdate() {
            @Override
            public void onOrderUpdate(com.zerodhatech.models.Order order) {
                onKiteOrderUpdate(order);
            }
        });
    }

    private void onConnect() {
        log.info("Kite ticker connected");
        connected = true;
        reconnectAttempts.set(0);
        degraded = false;

        // Resubscribe all instruments on reconnect
        resubscribe();
    }

    private void onDisconnect() {
        log.warn("Kite ticker disconnected");
        connected = false;

        int attempts = reconnectAttempts.incrementAndGet();
        if (attempts >= MAX_RECONNECT_RETRIES) {
            degraded = true;
            log.error(
                    "Kite ticker failed to reconnect after {} attempts. Entering DEGRADED state. "
                            + "Manual intervention or re-authentication required.",
                    attempts);
        } else {
            long delay = computeReconnectDelay(attempts);
            log.info("Reconnection attempt {}/{}, next retry in {}ms", attempts, MAX_RECONNECT_RETRIES, delay);
        }
    }

    /**
     * Computes exponential backoff delay: 1s, 2s, 4s, 8s, ... capped at MAX_RECONNECT_DELAY_MS.
     */
    long computeReconnectDelay(int attempt) {
        return Math.min(INITIAL_RECONNECT_DELAY_MS * (1L << (attempt - 1)), MAX_RECONNECT_DELAY_MS);
    }

    private void resubscribe() {
        if (!subscribedTokens.isEmpty() && kiteTicker != null && connected) {
            ArrayList<Long> tokens = new ArrayList<>(subscribedTokens);
            kiteTicker.subscribe(tokens);
            kiteTicker.setMode(tokens, KiteTicker.modeFull);
            log.info("Resubscribed {} instruments", tokens.size());
        }
    }

    /**
     * Callback for incoming order status updates from Kite WebSocket.
     * Delegates to {@link KiteOrderUpdateHandler} for safe processing of fills and rejections.
     *
     * <p>Wrapped in try/catch so order update processing failures never crash
     * the WebSocket connection (which also carries market data ticks).
     */
    private void onKiteOrderUpdate(com.zerodhatech.models.Order kiteOrder) {
        try {
            kiteOrderUpdateHandler.handleOrderUpdate(kiteOrder);
        } catch (Exception e) {
            log.error(
                    "Error processing order update for orderId={}: {}",
                    kiteOrder != null ? kiteOrder.orderId : "null",
                    e.getMessage(),
                    e);
        }
    }

    /**
     * Callback for incoming ticks. Maps each Kite SDK Tick to our domain model
     * and publishes a TickEvent for downstream processing.
     */
    private void onTicks(ArrayList<com.zerodhatech.models.Tick> ticks) {
        for (com.zerodhatech.models.Tick kiteTick : ticks) {
            Tick tick = mapToTick(kiteTick);
            applicationEventPublisher.publishEvent(new TickEvent(this, tick));
        }
    }

    /**
     * Maps a Kite SDK Tick to our domain Tick model.
     *
     * <p>Key mappings:
     * <ul>
     *   <li>Doubles -> BigDecimal (precision for financial data)</li>
     *   <li>java.util.Date timestamps -> LocalDateTime (IST timezone)</li>
     *   <li>Depth map -> List&lt;DepthItem&gt; (buy/sell separately)</li>
     * </ul>
     */
    private Tick mapToTick(com.zerodhatech.models.Tick kiteTick) {
        return Tick.builder()
                .instrumentToken(kiteTick.getInstrumentToken())
                .lastPrice(BigDecimal.valueOf(kiteTick.getLastTradedPrice()))
                .open(BigDecimal.valueOf(kiteTick.getOpenPrice()))
                .high(BigDecimal.valueOf(kiteTick.getHighPrice()))
                .low(BigDecimal.valueOf(kiteTick.getLowPrice()))
                .close(BigDecimal.valueOf(kiteTick.getClosePrice()))
                .volume(kiteTick.getVolumeTradedToday())
                .buyQuantity(BigDecimal.valueOf(kiteTick.getTotalBuyQuantity()))
                .sellQuantity(BigDecimal.valueOf(kiteTick.getTotalSellQuantity()))
                .oi(BigDecimal.valueOf(kiteTick.getOi()))
                .timestamp(convertTickTimestamp(kiteTick.getTickTimestamp()))
                .buyDepth(mapDepth(kiteTick.getMarketDepth(), "buy"))
                .sellDepth(mapDepth(kiteTick.getMarketDepth(), "sell"))
                .build();
    }

    /** Converts Kite's java.util.Date tick timestamp to LocalDateTime in IST. */
    private LocalDateTime convertTickTimestamp(java.util.Date date) {
        if (date == null) {
            return LocalDateTime.now();
        }
        return date.toInstant().atZone(ZoneId.of("Asia/Kolkata")).toLocalDateTime();
    }

    /**
     * Maps Kite depth data to our DepthItem list.
     * Kite returns depth as Map with "buy" and "sell" keys, each containing ArrayList of Depth.
     */
    private List<DepthItem> mapDepth(Map<String, ArrayList<Depth>> depth, String side) {
        if (depth == null || !depth.containsKey(side)) {
            return null;
        }

        return depth.get(side).stream()
                .map(d -> DepthItem.builder()
                        .quantity(d.getQuantity())
                        .price(BigDecimal.valueOf(d.getPrice()))
                        .orders(d.getOrders())
                        .build())
                .toList();
    }
}
