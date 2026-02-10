package com.algotrader.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * Response DTO for the chain explorer endpoint: GET /api/market-data/chain.
 *
 * <p>Returns the full derivative chain for a given underlying and expiry, including
 * the nearest future and all option strikes paired as call/put. Used by the
 * Instrument Explorer page to display a comprehensive chain view with live LTP tokens.
 *
 * <p>The frontend uses the tokens from this response to subscribe to WebSocket
 * tick updates for live price display.
 */
@Getter
@Builder
public class ChainExplorerResponse {

    /** Root underlying symbol (e.g., "NIFTY", "BANKNIFTY", "ADANIPORTS"). */
    private final String underlying;

    /** Display name of the underlying (e.g., "NIFTY 50", "ADANI PORT & SEZ"). */
    private final String displayName;

    /** The expiry date for this chain. */
    private final LocalDate expiry;

    /** Spot instrument token for the underlying (NSE equity/index). Null if no spot found. */
    private final Long spotToken;

    /** Contract lot size for derivatives of this underlying. */
    private final int lotSize;

    /** Nearest future for this underlying + expiry. Null if no future exists. */
    private final FutureInfo future;

    /** Option strikes sorted by strike price ascending, each with call and put side. */
    private final List<OptionStrikeInfo> options;

    /**
     * Compact representation of a futures contract — token and tradingSymbol
     * for WebSocket subscription and display.
     */
    @Getter
    @Builder
    public static class FutureInfo {

        private final long token;
        private final String tradingSymbol;
        private final int lotSize;
    }

    /**
     * A single strike level in the option chain, pairing the call and put sides.
     * Either side may be null if only CE or only PE exists at this strike.
     */
    @Getter
    @Builder
    public static class OptionStrikeInfo {

        private final BigDecimal strike;
        private final OptionSideInfo call;
        private final OptionSideInfo put;
        private final int lotSize;
    }

    /**
     * One side (CE or PE) of an option at a given strike — provides the token
     * and tradingSymbol needed for WebSocket subscription and display.
     */
    @Getter
    @Builder
    public static class OptionSideInfo {

        private final long token;
        private final String tradingSymbol;
    }
}
