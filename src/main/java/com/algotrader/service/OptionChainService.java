package com.algotrader.service;

import com.algotrader.core.processor.GreeksCalculator;
import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.model.Greeks;
import com.algotrader.domain.model.Instrument;
import com.algotrader.domain.model.OptionChain;
import com.algotrader.domain.model.OptionChainEntry;
import com.algotrader.domain.model.OptionData;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Quote;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Builds option chains by combining instrument data, live Kite quotes, and calculated Greeks.
 *
 * <p>Kite does NOT have an option chain endpoint — we construct it ourselves by:
 * <ol>
 *   <li>Getting instruments for the requested underlying and expiry from {@link InstrumentService}</li>
 *   <li>Fetching live quotes from Kite API (batched in groups of 500 to respect rate limits)</li>
 *   <li>Getting the spot price via Kite's LTP for the cash segment instrument</li>
 *   <li>Calculating Greeks for each option using {@link GreeksCalculator}</li>
 *   <li>Grouping by strike into {@link OptionChainEntry} rows (CE + PE per strike)</li>
 * </ol>
 *
 * <p>Option chains are cached in Caffeine with 60s TTL per (underlying, expiry) key to
 * balance quote API rate limits against data freshness. A typical NIFTY chain has
 * ~100 strikes x 2 (CE + PE) = ~200 instruments.
 *
 * <p>Strike selection helpers are provided for strategies that need specific moneyness
 * levels (ATM, specific delta targets, N strikes away from ATM).
 */
@Slf4j
@Service
public class OptionChainService {

    private static final int QUOTE_BATCH_SIZE = 500;

    private final InstrumentService instrumentService;
    private final KiteConnect kiteConnect;
    private final GreeksCalculator greeksCalculator;

    /** Caffeine cache: key = "underlying|expiry", value = OptionChain, 60s TTL. */
    private final Cache<String, OptionChain> chainCache;

    public OptionChainService(
            InstrumentService instrumentService, KiteConnect kiteConnect, GreeksCalculator greeksCalculator) {
        this.instrumentService = instrumentService;
        this.kiteConnect = kiteConnect;
        this.greeksCalculator = greeksCalculator;
        this.chainCache = Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(50)
                .build();
    }

    /**
     * Returns the option chain for the given underlying and expiry. Returns a cached
     * chain if available (within 60s TTL), otherwise builds a fresh one from live quotes.
     *
     * @param underlying the root underlying (e.g., "NIFTY", "BANKNIFTY")
     * @param expiry     the expiry date
     * @return the option chain with Greeks
     * @throws IllegalArgumentException if no instruments found for the underlying/expiry
     */
    public OptionChain getOptionChain(String underlying, LocalDate expiry) {
        String cacheKey = underlying + "|" + expiry;
        OptionChain cached = chainCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        OptionChain chain = buildOptionChain(underlying, expiry);
        chainCache.put(cacheKey, chain);
        return chain;
    }

    /**
     * Invalidates the cached option chain for the given underlying and expiry.
     * Used when a refresh is explicitly requested (e.g., after a trade).
     */
    public void invalidateCache(String underlying, LocalDate expiry) {
        chainCache.invalidate(underlying + "|" + expiry);
    }

    /**
     * Finds the ATM strike: the strike price nearest to the spot price.
     *
     * @param spotPrice the current spot price
     * @param strikes   available strike prices
     * @return the nearest strike, or null if strikes is empty
     */
    public BigDecimal findATMStrike(BigDecimal spotPrice, java.util.Collection<BigDecimal> strikes) {
        if (strikes == null || strikes.isEmpty()) {
            return null;
        }
        BigDecimal closest = null;
        BigDecimal minDiff = null;
        for (BigDecimal strike : strikes) {
            BigDecimal diff = strike.subtract(spotPrice).abs();
            if (minDiff == null || diff.compareTo(minDiff) < 0) {
                minDiff = diff;
                closest = strike;
            }
        }
        return closest;
    }

    /**
     * Selects a strike N positions away from ATM in the chain.
     * Positive offset = higher strike (OTM calls / ITM puts),
     * negative offset = lower strike (ITM calls / OTM puts).
     *
     * @param chain  the option chain
     * @param offset number of strikes away from ATM (positive = higher, negative = lower)
     * @return the strike at the requested offset, or null if out of range
     */
    public BigDecimal getStrikeByOffset(OptionChain chain, int offset) {
        if (chain == null || chain.getEntries() == null || chain.getAtmStrike() == null) {
            return null;
        }

        List<OptionChainEntry> entries = chain.getEntries();
        int atmIndex = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getStrike().compareTo(chain.getAtmStrike()) == 0) {
                atmIndex = i;
                break;
            }
        }
        if (atmIndex < 0) {
            return null;
        }

        int targetIndex = atmIndex + offset;
        if (targetIndex < 0 || targetIndex >= entries.size()) {
            return null;
        }
        return entries.get(targetIndex).getStrike();
    }

    /**
     * Finds the strike whose call delta is closest to the target delta.
     * Used by strategies that enter at a specific delta (e.g., "sell the 0.20 delta call").
     *
     * @param chain       the option chain
     * @param targetDelta the desired delta (e.g., 0.20 for a 20-delta call)
     * @return the OptionData for the closest match, or null
     */
    public OptionData findByDelta(OptionChain chain, double targetDelta, boolean isCall) {
        if (chain == null || chain.getEntries() == null) {
            return null;
        }

        OptionData closest = null;
        double minDiff = Double.MAX_VALUE;

        for (OptionChainEntry entry : chain.getEntries()) {
            OptionData option = isCall ? entry.getCall() : entry.getPut();
            if (option == null
                    || option.getGreeks() == null
                    || !option.getGreeks().isAvailable()) {
                continue;
            }

            double delta = Math.abs(option.getGreeks().getDelta().doubleValue());
            double diff = Math.abs(delta - Math.abs(targetDelta));
            if (diff < minDiff) {
                minDiff = diff;
                closest = option;
            }
        }

        return closest;
    }

    // ---- Private builders ----

    /**
     * Builds a fresh option chain from Kite instruments and live quotes.
     */
    private OptionChain buildOptionChain(String underlying, LocalDate expiry) {
        List<Instrument> instruments = instrumentService.getOptionsForUnderlying(underlying, expiry);

        if (instruments.isEmpty()) {
            throw new IllegalArgumentException("No instruments found for " + underlying + " expiry " + expiry);
        }

        // Build symbol array for Kite quote API
        String[] symbols =
                instruments.stream().map(i -> "NFO:" + i.getTradingSymbol()).toArray(String[]::new);

        // Fetch quotes (batched if > 500)
        Map<String, Quote> quotes = fetchQuotes(symbols);

        // Get spot price from cash segment
        BigDecimal spotPrice = getSpotPrice(underlying);

        // Build chain grouped by strike (TreeMap for natural ordering)
        TreeMap<BigDecimal, OptionChainEntry> chainMap = new TreeMap<>();

        for (Instrument inst : instruments) {
            BigDecimal strike = inst.getStrike();
            if (strike == null) continue;

            chainMap.computeIfAbsent(strike, OptionChainEntry::new);

            Quote quote = quotes.get("NFO:" + inst.getTradingSymbol());
            if (quote == null) continue;

            OptionData optionData = buildOptionData(inst, quote, spotPrice, expiry);

            if (inst.getType() == InstrumentType.CE) {
                chainMap.get(strike).setCall(optionData);
            } else if (inst.getType() == InstrumentType.PE) {
                chainMap.get(strike).setPut(optionData);
            }
        }

        BigDecimal atmStrike = findATMStrike(spotPrice, chainMap.keySet());

        return OptionChain.builder()
                .underlying(underlying)
                .spotPrice(spotPrice)
                .expiry(expiry)
                .atmStrike(atmStrike)
                .entries(new ArrayList<>(chainMap.values()))
                .build();
    }

    /**
     * Builds OptionData for a single instrument using its Kite quote and calculated Greeks.
     */
    private OptionData buildOptionData(Instrument inst, Quote quote, BigDecimal spotPrice, LocalDate expiry) {
        boolean isCall = inst.getType() == InstrumentType.CE;

        // Calculate Greeks (Kite doesn't provide these)
        Greeks greeks = greeksCalculator.calculate(
                spotPrice, inst.getStrike(), expiry, BigDecimal.valueOf(quote.lastPrice), isCall);

        // Extract best bid/ask from depth (may be null for some instruments)
        BigDecimal bidPrice = BigDecimal.ZERO;
        BigDecimal askPrice = BigDecimal.ZERO;
        if (quote.depth != null) {
            if (quote.depth.buy != null && !quote.depth.buy.isEmpty()) {
                bidPrice = BigDecimal.valueOf(quote.depth.buy.get(0).getPrice());
            }
            if (quote.depth.sell != null && !quote.depth.sell.isEmpty()) {
                askPrice = BigDecimal.valueOf(quote.depth.sell.get(0).getPrice());
            }
        }

        return OptionData.builder()
                .instrumentToken(inst.getToken())
                .tradingSymbol(inst.getTradingSymbol())
                .strike(inst.getStrike())
                .optionType(inst.getType().name())
                .ltp(BigDecimal.valueOf(quote.lastPrice))
                .change(BigDecimal.valueOf(quote.change))
                .oi((long) quote.oi)
                .volume((long) quote.volumeTradedToday)
                .bidPrice(bidPrice)
                .askPrice(askPrice)
                .greeks(greeks)
                .build();
    }

    /**
     * Fetches quotes from Kite API, batching if more than 500 instruments.
     * Kite's getQuote() supports a maximum of 500 instruments per call.
     */
    private Map<String, Quote> fetchQuotes(String[] symbols) {
        if (symbols.length <= QUOTE_BATCH_SIZE) {
            return fetchQuoteBatch(symbols);
        }

        // Batch into groups of 500
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
     * Gets the spot price for an underlying by fetching its NSE equity LTP.
     * For indices (NIFTY, BANKNIFTY), uses the index symbol on NSE.
     */
    private BigDecimal getSpotPrice(String underlying) {
        // #TODO Use real-time tick price from WebSocket when available (Task 3.3 integration)
        String symbol = "NSE:" + underlying;
        try {
            Map<String, Quote> quote = kiteConnect.getQuote(new String[] {symbol});
            Quote q = quote.get(symbol);
            if (q != null) {
                return BigDecimal.valueOf(q.lastPrice);
            }
        } catch (KiteException e) {
            log.warn("Failed to get spot price for {}: {}", underlying, e.message);
        } catch (Exception e) {
            log.warn("Failed to get spot price for {}", underlying, e);
        }

        log.warn("Could not fetch spot price for {}, using 0", underlying);
        return BigDecimal.ZERO;
    }
}
