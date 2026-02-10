package com.algotrader.domain;

import java.util.Collections;
import java.util.Map;

/**
 * Static mapping between index trading symbols and their F&amp;O underlying names.
 *
 * <p>NSE indices use display names with spaces (e.g., "NIFTY 50", "NIFTY BANK"),
 * while NFO derivatives use compact symbols (e.g., "NIFTY", "BANKNIFTY").
 * BSE indices like SENSEX trade derivatives on BFO.
 * This mapping enables linking index spot instruments to their F&amp;O derivatives.
 *
 * <p>Only F&amp;O-tradeable indices are included (those that have FUT/CE/PE on NFO or BFO).
 */
public final class IndexMapping {

    /**
     * Maps index tradingSymbol → F&amp;O underlying name.
     * Covers both NSE indices (→ NFO) and BSE indices (→ BFO).
     * E.g., "NIFTY 50" → "NIFTY", "SENSEX" → "SENSEX".
     */
    public static final Map<String, String> NSE_TO_NFO = Map.of(
            "NIFTY 50", "NIFTY",
            "NIFTY BANK", "BANKNIFTY",
            "NIFTY FIN SERVICE", "FINNIFTY",
            "NIFTY MID SELECT", "MIDCPNIFTY",
            "NIFTY NEXT 50", "NIFTYNXT50",
            // BSE indices → BFO underlying names
            "SENSEX", "SENSEX",
            "BANKEX", "BANKEX");

    /**
     * Reverse map: F&amp;O underlying name → index tradingSymbol.
     * E.g., "NIFTY" → "NIFTY 50", "SENSEX" → "SENSEX".
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
