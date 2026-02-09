package com.algotrader.domain.model;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * Market data and Greeks for a single option (CE or PE) at a specific strike.
 *
 * <p>Combines Kite quote data (LTP, volume, OI, bid/ask from depth) with locally
 * calculated Greeks. Used as a building block of {@link OptionChainEntry}, which
 * pairs a call and put at the same strike.
 */
@Data
@Builder
public class OptionData {

    private Long instrumentToken;
    private String tradingSymbol;
    private BigDecimal strike;

    /** "CE" or "PE". */
    private String optionType;

    private BigDecimal ltp;

    /** Net change from previous close. */
    private BigDecimal change;

    /** Open interest — total outstanding contracts. */
    private long oi;

    private long volume;

    /** Best bid (buy) price from order book depth. */
    private BigDecimal bidPrice;

    /** Best ask (sell) price from order book depth. */
    private BigDecimal askPrice;

    /** Black-Scholes Greeks calculated from LTP and spot price. */
    private Greeks greeks;
}
