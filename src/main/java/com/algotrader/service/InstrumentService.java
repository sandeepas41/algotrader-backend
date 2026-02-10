package com.algotrader.service;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>On startup (after token acquisition), checks H2 for today's instruments
 * by download_date. If present, loads from H2 into in-memory cache. If not,
 * downloads from Kite API ({@code kiteConnect.getInstruments("NFO")}), persists
 * to H2 with today's date, and populates the cache.
 *
 * <p>Two in-memory caches provide O(1) lookups:
 * <ul>
 *   <li>{@code tokenCache} — keyed by instrument token for tick processing</li>
 *   <li>{@code underlyingCache} — keyed by underlying (e.g., "NIFTY") for
 *       option chain construction, containing only CE/PE instruments</li>
 * </ul>
 *
 * <p>Instrument data is refreshed daily because Kite publishes new instrument
 * dumps each trading day with updated tokens, lot sizes, and new expiry series.
 */
@Service
public class InstrumentService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentService.class);

    private final InstrumentJpaRepository instrumentJpaRepository;
    private final InstrumentMapper instrumentMapper;
    private final KiteConnect kiteConnect;

    /** O(1) lookup by instrument token — used during tick processing. */
    private final Map<Long, Instrument> tokenCache = new ConcurrentHashMap<>();

    /** Options grouped by underlying — used for option chain construction. Only CE/PE instruments. */
    private final Map<String, List<Instrument>> underlyingCache = new ConcurrentHashMap<>();

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
     * Returns all distinct expiry dates for a given underlying, sorted ascending.
     *
     * @param underlying the root underlying, e.g., "NIFTY"
     * @return sorted list of expiry dates
     */
    public List<LocalDate> getExpiries(String underlying) {
        return underlyingCache.getOrDefault(underlying, Collections.emptyList()).stream()
                .map(Instrument::getExpiry)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Returns all instruments for a given underlying (all expiries, all types).
     *
     * @param underlying the root underlying
     * @return list of option instruments for the underlying
     */
    public List<Instrument> getInstrumentsByUnderlying(String underlying) {
        return underlyingCache.getOrDefault(underlying, Collections.emptyList());
    }

    /**
     * Returns all underlying names that have instruments in the cache.
     *
     * @return set of underlying names (e.g., "NIFTY", "BANKNIFTY")
     */
    public java.util.Set<String> getAvailableUnderlyings() {
        return Collections.unmodifiableSet(underlyingCache.keySet());
    }

    /**
     * Returns the total number of instruments currently cached in memory.
     */
    public int getCachedInstrumentCount() {
        return tokenCache.size();
    }

    /**
     * Searches instruments by trading symbol prefix (case-insensitive).
     * Scans the token cache; intended for UI search, not for hot-path tick processing.
     *
     * @param symbolPrefix the prefix to match against tradingSymbol
     * @return list of matching instruments
     */
    public List<Instrument> searchBySymbol(String symbolPrefix) {
        String upperPrefix = symbolPrefix.toUpperCase();
        return tokenCache.values().stream()
                .filter(i -> i.getTradingSymbol() != null
                        && i.getTradingSymbol().toUpperCase().startsWith(upperPrefix))
                .toList();
    }

    // ---- Private helpers ----

    private void loadFromH2(LocalDate downloadDate) {
        List<InstrumentEntity> entities = instrumentJpaRepository.findByDownloadDate(downloadDate);
        List<Instrument> instruments = instrumentMapper.toDomainList(entities);
        populateCaches(instruments);
        log.info("Loaded {} instruments from H2 into cache", instruments.size());
    }

    private void downloadAndSave(LocalDate today) {
        try {
            List<com.zerodhatech.models.Instrument> kiteInstruments = kiteConnect.getInstruments("NFO");
            log.info("Downloaded {} NFO instruments from Kite API", kiteInstruments.size());

            List<Instrument> instruments =
                    kiteInstruments.stream().map(ki -> mapFromKite(ki, today)).toList();

            // Persist to H2 — convert domain -> entity via MapStruct
            List<InstrumentEntity> entities =
                    instruments.stream().map(instrumentMapper::toEntity).toList();

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

            populateCaches(instruments);
            log.info("Instruments downloaded, saved, and cached successfully");
        } catch (KiteException | JSONException | IOException e) {
            throw new BrokerException("Failed to download instruments from Kite API: " + e.getMessage(), e);
        }
    }

    /**
     * Populates both in-memory caches from a list of domain instruments.
     * tokenCache: all instruments keyed by token.
     * underlyingCache: only CE/PE instruments grouped by underlying (name field).
     */
    private void populateCaches(List<Instrument> instruments) {
        tokenCache.clear();
        underlyingCache.clear();

        for (Instrument instrument : instruments) {
            if (instrument.getToken() != null) {
                tokenCache.put(instrument.getToken(), instrument);
            }
        }

        // Group options (CE/PE) by underlying for option chain construction
        Map<String, List<Instrument>> optionsByUnderlying = instruments.stream()
                .filter(i -> i.getType() != null
                        && ("CE".equals(i.getType().name())
                                || "PE".equals(i.getType().name())))
                .filter(i -> i.getUnderlying() != null)
                .collect(Collectors.groupingBy(Instrument::getUnderlying));

        underlyingCache.putAll(optionsByUnderlying);

        log.info(
                "Cache populated: {} total instruments, {} underlyings with options",
                tokenCache.size(),
                underlyingCache.size());
    }

    /**
     * Maps a Kite SDK Instrument to our domain model.
     *
     * <p>Key field mappings from Kite SDK:
     * <ul>
     *   <li>{@code instrument_token} (long) -> {@code token} (Long)</li>
     *   <li>{@code tradingsymbol} (String, no underscore) -> {@code tradingSymbol}</li>
     *   <li>{@code name} (String) -> {@code underlying} (for NFO, name = underlying e.g., "NIFTY")</li>
     *   <li>{@code strike} (String!) -> {@code strike} (BigDecimal, parsed)</li>
     *   <li>{@code expiry} (java.util.Date) -> {@code expiry} (LocalDate, converted)</li>
     *   <li>{@code tick_size} (double) -> {@code tickSize} (BigDecimal)</li>
     *   <li>{@code instrument_type} (String) -> {@code type} (InstrumentType enum, null if unknown)</li>
     * </ul>
     */
    private Instrument mapFromKite(com.zerodhatech.models.Instrument kiteInstrument, LocalDate downloadDate) {
        return Instrument.builder()
                .token(kiteInstrument.instrument_token)
                .tradingSymbol(kiteInstrument.tradingsymbol)
                .name(kiteInstrument.name)
                // For NFO instruments, Kite's "name" field is the underlying (e.g., "NIFTY")
                .underlying(kiteInstrument.name)
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
     * Parses the Kite instrument_type string to our enum.
     * Returns null for unrecognized types (Kite has types beyond our enum).
     */
    private com.algotrader.domain.enums.InstrumentType parseInstrumentType(String kiteType) {
        if (kiteType == null || kiteType.isBlank()) {
            return null;
        }
        try {
            return com.algotrader.domain.enums.InstrumentType.valueOf(kiteType);
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
