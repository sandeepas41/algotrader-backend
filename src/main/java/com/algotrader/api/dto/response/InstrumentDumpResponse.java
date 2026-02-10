package com.algotrader.api.dto.response;

import lombok.Builder;
import lombok.Getter;

/**
 * Lightweight DTO for the bulk instrument dump endpoint.
 *
 * <p>Maps BE domain field names to FE conventions:
 * <ul>
 *   <li>{@code token} → {@code instrumentToken}</li>
 *   <li>{@code type} → {@code instrumentType} (String, not enum)</li>
 *   <li>{@code strike} → {@code Double} (nullable, from BigDecimal)</li>
 *   <li>{@code expiry} → {@code String} (ISO date, from LocalDate)</li>
 *   <li>{@code tickSize} → {@code Double} (from BigDecimal)</li>
 * </ul>
 *
 * <p>Used by the FE to populate IndexedDB (Dexie.js) for instant client-side
 * instrument queries. The full dump (~68k records) is fetched once per trading day.
 */
@Getter
@Builder
public class InstrumentDumpResponse {

    /** Kite's unique numeric instrument identifier. Maps to FE {@code instrumentToken}. */
    private final Long instrumentToken;

    private final String tradingSymbol;
    private final String name;
    private final String underlying;

    /** Instrument type as string: "EQ", "FUT", "CE", "PE". Null for unrecognized Kite types. */
    private final String instrumentType;

    /** Strike price for options. Null for equities and futures. */
    private final Double strike;

    /** ISO date string (e.g., "2026-02-27"). Null for equities. */
    private final String expiry;

    private final String exchange;
    private final String segment;
    private final int lotSize;
    private final double tickSize;
}
