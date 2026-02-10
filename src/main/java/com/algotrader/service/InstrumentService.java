package com.algotrader.service;

import com.algotrader.domain.IndexMapping;
import com.algotrader.domain.enums.InstrumentType;
import com.algotrader.domain.model.Instrument;
import com.algotrader.entity.InstrumentEntity;
import com.algotrader.exception.BrokerException;
import com.algotrader.mapper.InstrumentMapper;
import com.algotrader.repository.jpa.InstrumentJpaRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Manages the daily instrument lifecycle: loading from H2 or downloading from
 * Kite API, caching in memory, and providing fast lookups for option chain
 * construction and WebSocket subscriptions.
 *
 * <p>Downloads instruments from three exchanges daily: NFO (derivatives),
 * NSE (equities + indices), and BSE (equities). This gives us:
 * <ul>
 *   <li>NFO (~46k): FUT/CE/PE instruments with underlying = root symbol</li>
 *   <li>NSE (~9k): Equities (name = company display name) + Indices (NIFTY 50, etc.)</li>
 *   <li>BSE (~12k): Equities (name = company display name)</li>
 * </ul>
 *
 * <p>Four in-memory caches provide O(1) lookups:
 * <ul>
 *   <li>{@code tokenCache} — all instruments keyed by token (tick processing)</li>
 *   <li>{@code underlyingCache} — CE/PE grouped by underlying (option chain construction)</li>
 *   <li>{@code derivativesCache} — CE/PE/FUT grouped by underlying (explorer chain)</li>
 *   <li>{@code spotCache} — NSE/BSE equities + indices keyed by underlying symbol (spot lookups)</li>
 * </ul>
 */
@Service
public class InstrumentService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentService.class);

    /** Exchanges to download instruments from. */
    private static final List<String> EXCHANGES = List.of("NFO", "NSE", "BSE");

    private final InstrumentJpaRepository instrumentJpaRepository;
    private final InstrumentMapper instrumentMapper;
    private final KiteConnect kiteConnect;

    /** O(1) lookup by instrument token — used during tick processing. */
    private final Map<Long, Instrument> tokenCache = new ConcurrentHashMap<>();

    /** Options (CE/PE only) grouped by underlying — used for option chain construction. */
    private final Map<String, List<Instrument>> underlyingCache = new ConcurrentHashMap<>();

    /** All NFO derivatives (CE/PE/FUT) grouped by underlying — used for explorer chain. */
    private final Map<String, List<Instrument>> derivativesCache = new ConcurrentHashMap<>();

    /**
     * Spot instruments keyed by underlying symbol. For NSE/BSE equities, key = tradingSymbol
     * (e.g., "ADANIPORTS"). For F&amp;O indices, key = NFO underlying name (e.g., "NIFTY").
     * Used for spot price token lookups.
     */
    private final Map<String, Instrument> spotCache = new ConcurrentHashMap<>();

    public InstrumentService(
            InstrumentJpaRepository instrumentJpaRepository,
            InstrumentMapper instrumentMapper,
            KiteConnect kiteConnect) {
        this.instrumentJpaRepository = instrumentJpaRepository;
        this.instrumentMapper = instrumentMapper;
        this.kiteConnect = kiteConnect;
    }

    /**
     * Loads today's instruments into memory. Called on startup AFTER token acquisition.
     * Checks H2 first; downloads from Kite API if today's data is absent.
     *
     * @throws BrokerException if the Kite API download fails
     */
    public void loadInstrumentsOnStartup() {
        LocalDate today = LocalDate.now();

        if (instrumentJpaRepository.existsByDownloadDate(today)) {
            log.info("Instruments for today ({}) found in H2, loading from DB...", today);
            loadFromH2(today);
            return;
        }

        log.info("No instruments in H2 for today ({}), downloading from Kite API...", today);
        downloadAndSave(today);
    }

    /**
     * Finds an instrument by its Kite token. O(1) from in-memory cache.
     *
     * @param token the Kite instrument token
     * @return the instrument, or empty if not found
     */
    public Optional<Instrument> findByToken(long token) {
        return Optional.ofNullable(tokenCache.get(token));
    }

    /**
     * Returns the spot instrument for a given underlying symbol.
     * For equities: pass the NSE tradingSymbol (e.g., "ADANIPORTS").
     * For indices: pass the NFO underlying (e.g., "NIFTY", "BANKNIFTY").
     *
     * @param underlying the underlying symbol
     * @return the spot instrument, or empty if not found
     */
    public Optional<Instrument> getSpotInstrument(String underlying) {
        return Optional.ofNullable(spotCache.get(underlying));
    }

    /**
     * Returns all option instruments (CE/PE) for a given underlying and expiry date.
     * Used for building the option chain.
     *
     * @param underlying the root underlying, e.g., "NIFTY", "BANKNIFTY"
     * @param expiry the expiry date to filter by
     * @return list of matching option instruments, empty if none found
     */
    public List<Instrument> getOptionsForUnderlying(String underlying, LocalDate expiry) {
        return underlyingCache.getOrDefault(underlying, Collections.emptyList()).stream()
                .filter(i -> expiry.equals(i.getExpiry()))
                .toList();
    }

    /**
     * Returns all NFO derivatives (CE/PE/FUT) for a given underlying and expiry date.
     * Used by the explorer chain view which includes futures.
     *
     * @param underlying the root underlying, e.g., "NIFTY", "BANKNIFTY"
     * @param expiry the expiry date to filter by
     * @return list of matching derivative instruments, empty if none found
     */
    public List<Instrument> getDerivativesForExpiry(String underlying, LocalDate expiry) {
        return derivativesCache.getOrDefault(underlying, Collections.emptyList()).stream()
                .filter(i -> expiry.equals(i.getExpiry()))
                .toList();
    }

    /**
     * Returns all distinct expiry dates for a given underlying, sorted ascending.
     * Includes both option and future expiries.
     *
     * @param underlying the root underlying, e.g., "NIFTY"
     * @return sorted list of expiry dates
     */
    public List<LocalDate> getExpiries(String underlying) {
        return derivativesCache.getOrDefault(underlying, Collections.emptyList()).stream()
                .map(Instrument::getExpiry)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Returns all instruments for a given underlying (all expiries, all types).
     * Returns CE/PE only (from underlyingCache) for backward compatibility with
     * option chain service.
     *
     * @param underlying the root underlying
     * @return list of option instruments for the underlying
     */
    public List<Instrument> getInstrumentsByUnderlying(String underlying) {
        return underlyingCache.getOrDefault(underlying, Collections.emptyList());
    }

    /**
     * Returns all underlying names that have NFO derivatives in the cache.
     *
     * @return set of underlying names (e.g., "NIFTY", "BANKNIFTY", "RELIANCE")
     */
    public Set<String> getAvailableUnderlyings() {
        return Collections.unmodifiableSet(derivativesCache.keySet());
    }

    /**
     * Returns the total number of instruments currently cached in memory.
     */
    public int getCachedInstrumentCount() {
        return tokenCache.size();
    }

    /**
     * Searches instruments by query string (case-insensitive).
     * Matches against tradingSymbol prefix, name (contains), and underlying (prefix).
     * Scans the token cache; intended for UI search, not for hot-path tick processing.
     *
     * @param query the search term
     * @return list of matching instruments (limited to 50 results)
     */
    public List<Instrument> searchInstruments(String query) {
        String upperQuery = query.toUpperCase();
        return tokenCache.values().stream()
                .filter(i -> matchesSearch(i, upperQuery))
                .limit(50)
                .toList();
    }

    /**
     * Searches instruments by query string with optional exchange filter.
     *
     * @param query the search term
     * @param exchange optional exchange filter (e.g., "NSE", "NFO", "BSE"), null for all
     * @return list of matching instruments (limited to 50 results)
     */
    public List<Instrument> searchInstruments(String query, String exchange) {
        String upperQuery = query.toUpperCase();
        return tokenCache.values().stream()
                .filter(i -> exchange == null || exchange.equalsIgnoreCase(i.getExchange()))
                .filter(i -> matchesSearch(i, upperQuery))
                .limit(50)
                .toList();
    }

    // ---- Private helpers ----

    private boolean matchesSearch(Instrument instrument, String upperQuery) {
        String symbol = instrument.getTradingSymbol();
        String name = instrument.getName();
        String underlying = instrument.getUnderlying();

        return (symbol != null && symbol.toUpperCase().startsWith(upperQuery))
                || (name != null && name.toUpperCase().contains(upperQuery))
                || (underlying != null && underlying.toUpperCase().startsWith(upperQuery));
    }

    private void loadFromH2(LocalDate downloadDate) {
        List<InstrumentEntity> entities = instrumentJpaRepository.findByDownloadDate(downloadDate);
        List<Instrument> instruments = instrumentMapper.toDomainList(entities);
        populateCaches(instruments);
        log.info("Loaded {} instruments from H2 into cache", instruments.size());
    }

    private void downloadAndSave(LocalDate today) {
        try {
            List<Instrument> allInstruments = new ArrayList<>();

            for (String exchange : EXCHANGES) {
                List<com.zerodhatech.models.Instrument> kiteInstruments = kiteConnect.getInstruments(exchange);
                log.info("Downloaded {} {} instruments from Kite API", kiteInstruments.size(), exchange);

                List<Instrument> instruments = kiteInstruments.stream()
                        .map(ki -> mapFromKite(ki, today))
                        .toList();
                allInstruments.addAll(instruments);
            }

            log.info("Total instruments downloaded: {}", allInstruments.size());

            // Persist to H2 — convert domain -> entity via MapStruct
            List<InstrumentEntity> entities =
                    allInstruments.stream().map(instrumentMapper::toEntity).toList();

            // Set downloadDate and createdAt on entities (ignored by MapStruct toEntity)
            LocalDateTime now = LocalDateTime.now();
            entities.forEach(e -> {
                e.setDownloadDate(today);
                e.setCreatedAt(now);
            });

            // Truncate old data before saving — only today's instruments are needed
            instrumentJpaRepository.deleteAllInBatch();
            instrumentJpaRepository.saveAll(entities);
            log.info("Saved {} instruments to H2 for date {}", entities.size(), today);

            populateCaches(allInstruments);
            log.info("Instruments downloaded, saved, and cached successfully");
        } catch (KiteException | JSONException | IOException e) {
            throw new BrokerException("Failed to download instruments from Kite API: " + e.getMessage(), e);
        }
    }

    /**
     * Populates all four in-memory caches from a list of domain instruments.
     *
     * <ul>
     *   <li>{@code tokenCache}: all instruments keyed by token</li>
     *   <li>{@code underlyingCache}: NFO CE/PE grouped by underlying</li>
     *   <li>{@code derivativesCache}: NFO CE/PE/FUT grouped by underlying</li>
     *   <li>{@code spotCache}: NSE/BSE equities keyed by underlying symbol,
     *       indices keyed by NFO underlying name via {@link IndexMapping}</li>
     * </ul>
     */
    private void populateCaches(List<Instrument> instruments) {
        tokenCache.clear();
        underlyingCache.clear();
        derivativesCache.clear();
        spotCache.clear();

        for (Instrument instrument : instruments) {
            if (instrument.getToken() != null) {
                tokenCache.put(instrument.getToken(), instrument);
            }
        }

        // Group CE/PE by underlying for option chain construction
        Map<String, List<Instrument>> optionsByUnderlying = instruments.stream()
                .filter(i ->
                        i.getType() != null && (InstrumentType.CE == i.getType() || InstrumentType.PE == i.getType()))
                .filter(i -> i.getUnderlying() != null)
                .collect(Collectors.groupingBy(Instrument::getUnderlying));
        underlyingCache.putAll(optionsByUnderlying);

        // Group CE/PE/FUT by underlying for explorer chain
        Map<String, List<Instrument>> derivativesByUnderlying = instruments.stream()
                .filter(i -> i.getType() != null
                        && (InstrumentType.CE == i.getType()
                                || InstrumentType.PE == i.getType()
                                || InstrumentType.FUT == i.getType()))
                .filter(i -> i.getUnderlying() != null)
                .collect(Collectors.groupingBy(Instrument::getUnderlying));
        derivativesCache.putAll(derivativesByUnderlying);

        // Populate spot cache: NSE/BSE equities + F&O-tradeable indices
        for (Instrument instrument : instruments) {
            if (instrument.getType() != InstrumentType.EQ) {
                continue;
            }
            String segment = instrument.getSegment();
            if ("INDICES".equals(segment)) {
                // For indices, key by NFO underlying name (e.g., "NIFTY" for "NIFTY 50")
                String nfoUnderlying = IndexMapping.toNfoUnderlying(instrument.getTradingSymbol());
                if (nfoUnderlying != null) {
                    spotCache.put(nfoUnderlying, instrument);
                }
            } else if ("NSE".equals(segment) || "BSE".equals(segment)) {
                // For equities, key by tradingSymbol (e.g., "ADANIPORTS")
                // Prefer NSE over BSE if both exist
                if (!"BSE".equals(segment) || !spotCache.containsKey(instrument.getTradingSymbol())) {
                    spotCache.put(instrument.getTradingSymbol(), instrument);
                }
            }
        }

        log.info(
                "Cache populated: {} total instruments, {} underlyings with options, "
                        + "{} underlyings with derivatives, {} spot instruments",
                tokenCache.size(),
                underlyingCache.size(),
                derivativesCache.size(),
                spotCache.size());
    }

    /**
     * Maps a Kite SDK Instrument to our domain model with exchange-aware field semantics.
     *
     * <p>Field mapping differs by exchange:
     * <ul>
     *   <li><b>NFO</b>: name = kite.name (= underlying ticker, e.g., "ADANIPORTS"),
     *       underlying = kite.name</li>
     *   <li><b>NSE/BSE equities</b>: name = kite.name (display name, e.g., "ADANI PORT &amp; SEZ"),
     *       underlying = kite.tradingsymbol (ticker, e.g., "ADANIPORTS")</li>
     *   <li><b>NSE INDICES</b>: name = kite.name (e.g., "NIFTY 50"),
     *       underlying = NFO name via {@link IndexMapping} (e.g., "NIFTY"), null if not F&amp;O index</li>
     * </ul>
     */
    private Instrument mapFromKite(com.zerodhatech.models.Instrument kiteInstrument, LocalDate downloadDate) {
        String name = kiteInstrument.name;
        String underlying = resolveUnderlying(kiteInstrument);

        return Instrument.builder()
                .token(kiteInstrument.instrument_token)
                .tradingSymbol(kiteInstrument.tradingsymbol)
                .name(name)
                .underlying(underlying)
                .exchange(kiteInstrument.exchange)
                .segment(kiteInstrument.segment)
                .type(parseInstrumentType(kiteInstrument.instrument_type))
                .strike(parseStrike(kiteInstrument.strike))
                .expiry(convertToLocalDate(kiteInstrument.expiry))
                .lotSize(kiteInstrument.lot_size)
                .tickSize(BigDecimal.valueOf(kiteInstrument.tick_size))
                .downloadDate(downloadDate)
                .build();
    }

    /**
     * Resolves the underlying symbol based on exchange and segment.
     *
     * <p>For NFO: underlying = kite.name (e.g., "NIFTY", "ADANIPORTS").
     * For NSE/BSE equities: underlying = kite.tradingsymbol (the ticker).
     * For NSE INDICES: underlying = NFO name via IndexMapping, or null if not F&amp;O.
     */
    private String resolveUnderlying(com.zerodhatech.models.Instrument ki) {
        String segment = ki.segment;

        // NFO derivatives: name IS the underlying symbol
        if (segment != null && segment.startsWith("NFO")) {
            return ki.name;
        }

        // NSE/BSE INDICES: map to NFO underlying via IndexMapping
        if ("INDICES".equals(segment)) {
            return IndexMapping.toNfoUnderlying(ki.tradingsymbol);
        }

        // NSE/BSE equities: tradingsymbol IS the underlying (ticker)
        return ki.tradingsymbol;
    }

    /**
     * Parses the Kite instrument_type string to our enum.
     * Returns null for unrecognized types (Kite has types beyond our enum).
     */
    private InstrumentType parseInstrumentType(String kiteType) {
        if (kiteType == null || kiteType.isBlank()) {
            return null;
        }
        try {
            return InstrumentType.valueOf(kiteType);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parses Kite's strike field (String) to BigDecimal.
     * Kite uses String for strike (e.g., "22000.0"), returns null for empty/unparseable.
     */
    private BigDecimal parseStrike(String strike) {
        if (strike == null || strike.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(strike);
        } catch (NumberFormatException e) {
            log.warn("Could not parse strike value: {}", strike);
            return null;
        }
    }

    /** Converts Kite's java.util.Date expiry to LocalDate. Returns null if input is null. */
    private LocalDate convertToLocalDate(java.util.Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
    }
}
