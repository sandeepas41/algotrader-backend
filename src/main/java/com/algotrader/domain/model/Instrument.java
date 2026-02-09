package com.algotrader.domain.model;

import com.algotrader.domain.enums.InstrumentType;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;

/**
 * A tradeable instrument (equity, future, or option) from the NSE/NFO exchanges.
 *
 * <p>Instruments are downloaded daily from Kite API's instruments dump and cached in H2
 * by downloadDate. On each market day, the InstrumentService checks if today's instruments
 * exist in H2; if not, it downloads fresh data from Kite. In-memory lookups use a
 * ConcurrentHashMap keyed by token for O(1) access during tick processing.
 *
 * <p>The token is Kite's unique numeric identifier for an instrument and is used
 * for WebSocket subscriptions and all market data operations.
 */
@Data
@Builder
public class Instrument {

    /** Kite's unique numeric instrument identifier, used for WebSocket subscriptions. */
    private Long token;

    /** Exchange trading symbol, e.g., "NIFTY24FEB22000CE". */
    private String tradingSymbol;

    private String name;

    /** Root underlying, e.g., "NIFTY", "BANKNIFTY". Null for equities. */
    private String underlying;

    private InstrumentType type;

    /** Strike price for options. Null for equities and futures. */
    private BigDecimal strike;

    /** Expiry date for F&O instruments. Null for equities. */
    private LocalDate expiry;

    /** Exchange code: "NSE" or "NFO". */
    private String exchange;

    private String segment;

    /** Contract lot size (e.g., NIFTY=75, BANKNIFTY=15). Always a whole number. */
    private int lotSize;

    /** Minimum price movement for this instrument. */
    private BigDecimal tickSize;

    /** Date when this instrument data was downloaded from Kite API. */
    private LocalDate downloadDate;
}
