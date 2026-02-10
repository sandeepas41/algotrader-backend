package com.algotrader.domain;

import java.util.Collections;
import java.util.Map;

/**
 * Static mapping between NSE index trading symbols and their NFO underlying names.
 *
 * <p>NSE indices use display names with spaces (e.g., "NIFTY 50", "NIFTY BANK"),
 * while NFO derivatives use compact symbols (e.g., "NIFTY", "BANKNIFTY").
 * This mapping enables linking an NSE index spot instrument to its NFO derivatives.
 *
 * <p>Only F&amp;O-tradeable indices are included (those that have FUT/CE/PE on NFO).
 */
public final class IndexMapping {

    /**
     * Maps NSE index tradingSymbol → NFO underlying name.
     * E.g., "NIFTY 50" → "NIFTY", "NIFTY BANK" → "BANKNIFTY".
     */
    public static final Map<String, String> NSE_TO_NFO = Map.of(
            "NIFTY 50", "NIFTY",
            "NIFTY BANK", "BANKNIFTY",
            "NIFTY FIN SERVICE", "FINNIFTY",
            "NIFTY MID SELECT", "MIDCPNIFTY",
            "NIFTY NEXT 50", "NIFTYNXT50");

    /**
     * Reverse map: NFO underlying name → NSE index tradingSymbol.
     * E.g., "NIFTY" → "NIFTY 50", "BANKNIFTY" → "NIFTY BANK".
     */
    public static final Map<String, String> NFO_TO_NSE;

    static {
        var reverse = new java.util.HashMap<String, String>();
        NSE_TO_NFO.forEach((nse, nfo) -> reverse.put(nfo, nse));
        NFO_TO_NSE = Collections.unmodifiableMap(reverse);
    }

    private IndexMapping() {}

    /**
     * Returns the NFO underlying name for an NSE index tradingSymbol, or null if not an F&amp;O index.
     */
    public static String toNfoUnderlying(String nseIndexSymbol) {
        return NSE_TO_NFO.get(nseIndexSymbol);
    }

    /**
     * Returns true if the given NSE tradingSymbol is a known F&amp;O-tradeable index.
     */
    public static boolean isFnOIndex(String nseIndexSymbol) {
        return NSE_TO_NFO.containsKey(nseIndexSymbol);
    }
}
