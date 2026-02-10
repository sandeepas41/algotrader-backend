package com.algotrader.service;

import com.algotrader.domain.model.DepthItem;
import com.algotrader.domain.model.Instrument;
import com.algotrader.domain.model.Tick;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Depth;
import com.zerodhatech.models.Quote;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Fetches live market quotes from Kite REST API and maps them to domain Tick objects.
 *
 * <p>Used for initial LTP seeding — the FE calls this on page load to display prices
 * immediately, before WebSocket ticks arrive. After seeding, WebSocket takes over
 * for real-time updates.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Token → symbol resolution via InstrumentService</li>
 *   <li>Batch quote fetching (max 500 instruments per Kite API call)</li>
 *   <li>Quote → Tick mapping with BigDecimal conversion for financial precision</li>
 *   <li>Partial result handling — skips unknown tokens, returns what succeeds</li>
 * </ul>
 */
@Slf4j
@Service
public class QuoteService {

    private static final int QUOTE_BATCH_SIZE = 500;

    private final KiteConnect kiteConnect;
    private final InstrumentService instrumentService;

    public QuoteService(KiteConnect kiteConnect, InstrumentService instrumentService) {
        this.kiteConnect = kiteConnect;
        this.instrumentService = instrumentService;
    }

    /**
     * Fetches quotes for the given instrument tokens and returns them as domain Tick objects.
     *
     * <p>Flow:
     * <ol>
     *   <li>Resolves each token to an Instrument via InstrumentService (skips unknown tokens)</li>
     *   <li>Builds Kite symbol strings: "exchange:tradingSymbol"</li>
     *   <li>Fetches quotes in batches of 500 (Kite API limit)</li>
     *   <li>Maps each Quote to a domain Tick</li>
     * </ol>
     *
     * @param instrumentTokens list of Kite instrument tokens
     * @return list of Tick objects (may be smaller than input if tokens are unknown or batch fails)
     */
    public List<Tick> getQuotes(List<Long> instrumentTokens) {
        // Resolve tokens to instruments, building symbol→token lookup
        Map<String, Long> symbolToToken = new HashMap<>();
        List<String> symbols = new ArrayList<>();

        for (Long token : instrumentTokens) {
            Optional<Instrument> instrument = instrumentService.findByToken(token);
            if (instrument.isEmpty()) {
                log.debug("Skipping unknown instrument token: {}", token);
                continue;
            }
            Instrument inst = instrument.get();
            String symbol = inst.getExchange() + ":" + inst.getTradingSymbol();
            symbols.add(symbol);
            symbolToToken.put(symbol, token);
        }

        if (symbols.isEmpty()) {
            return List.of();
        }

        // Fetch quotes from Kite (batched)
        Map<String, Quote> quotes = fetchQuotes(symbols.toArray(String[]::new));

        // Map Quote → Tick
        List<Tick> ticks = new ArrayList<>();
        for (Map.Entry<String, Quote> entry : quotes.entrySet()) {
            Long token = symbolToToken.get(entry.getKey());
            if (token == null) {
                // Kite may return keys in a different format; try matching by instrumentToken
                token = entry.getValue().instrumentToken;
            }
            ticks.add(mapQuoteToTick(entry.getValue(), token));
        }

        return ticks;
    }

    /**
     * Fetches quotes from Kite API, batching if more than 500 instruments.
     * Returns partial results on batch failure (fail-safe).
     */
    private Map<String, Quote> fetchQuotes(String[] symbols) {
        if (symbols.length <= QUOTE_BATCH_SIZE) {
            return fetchQuoteBatch(symbols);
        }

        Map<String, Quote> allQuotes = new HashMap<>();
        for (int i = 0; i < symbols.length; i += QUOTE_BATCH_SIZE) {
            String[] batch = Arrays.copyOfRange(symbols, i, Math.min(i + QUOTE_BATCH_SIZE, symbols.length));
            allQuotes.putAll(fetchQuoteBatch(batch));
        }
        return allQuotes;
    }

    /**
     * Fetches a single batch of quotes from Kite.
     * KiteException extends Throwable (not Exception), so we catch it explicitly.
     */
    private Map<String, Quote> fetchQuoteBatch(String[] symbols) {
        try {
            return kiteConnect.getQuote(symbols);
        } catch (KiteException e) {
            log.error("Failed to fetch quotes from Kite: {}", e.message);
            return Map.of();
        } catch (Exception e) {
            log.error("Failed to fetch quotes from Kite", e);
            return Map.of();
        }
    }

    /**
     * Maps a Kite Quote to our domain Tick model.
     *
     * <p>Key mappings:
     * <ul>
     *   <li>Doubles → BigDecimal for financial precision</li>
     *   <li>OHLC nested object → flat fields</li>
     *   <li>java.util.Date → LocalDateTime in IST</li>
     *   <li>Depth (MarketDepth) → List of DepthItem (buy/sell separately)</li>
     *   <li>oiChange set to 0 — not available in Quote (only in WebSocket ticks)</li>
     * </ul>
     */
    private Tick mapQuoteToTick(Quote quote, Long token) {
        return Tick.builder()
                .instrumentToken(token)
                .lastPrice(BigDecimal.valueOf(quote.lastPrice))
                .open(quote.ohlc != null ? BigDecimal.valueOf(quote.ohlc.open) : BigDecimal.ZERO)
                .high(quote.ohlc != null ? BigDecimal.valueOf(quote.ohlc.high) : BigDecimal.ZERO)
                .low(quote.ohlc != null ? BigDecimal.valueOf(quote.ohlc.low) : BigDecimal.ZERO)
                .close(quote.ohlc != null ? BigDecimal.valueOf(quote.ohlc.close) : BigDecimal.ZERO)
                .volume((long) quote.volumeTradedToday)
                .buyQuantity(BigDecimal.valueOf(quote.buyQuantity))
                .sellQuantity(BigDecimal.valueOf(quote.sellQuantity))
                .oi(BigDecimal.valueOf(quote.oi))
                .oiChange(BigDecimal.ZERO) // Not available in Quote REST API
                .timestamp(convertTimestamp(quote.timestamp))
                .buyDepth(mapDepth(quote.depth, true))
                .sellDepth(mapDepth(quote.depth, false))
                .build();
    }

    /** Converts Kite's java.util.Date to LocalDateTime in IST. */
    private LocalDateTime convertTimestamp(Date date) {
        if (date == null) {
            return LocalDateTime.now();
        }
        return date.toInstant().atZone(ZoneId.of("Asia/Kolkata")).toLocalDateTime();
    }

    /** Maps Kite depth data to our DepthItem list. */
    private List<DepthItem> mapDepth(com.zerodhatech.models.MarketDepth depth, boolean isBuy) {
        if (depth == null) {
            return null;
        }
        List<Depth> side = isBuy ? depth.buy : depth.sell;
        if (side == null || side.isEmpty()) {
            return null;
        }
        return side.stream()
                .map(d -> DepthItem.builder()
                        .quantity(d.getQuantity())
                        .price(BigDecimal.valueOf(d.getPrice()))
                        .orders(d.getOrders())
                        .build())
                .toList();
    }
}
