package com.algotrader.domain.vo;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Value;

/**
 * Immutable itemized breakdown of all transaction charges for Indian F&O trades.
 * Used in Trade and StrategyRun to enable accurate tax reporting and cost analysis.
 *
 * <p>Charge components:
 * <ul>
 *   <li>brokerage — broker fee (flat Rs 20 per order for discount brokers)</li>
 *   <li>stt — Securities Transaction Tax (charged on sell-side only for options)</li>
 *   <li>exchangeCharges — NSE/BSE turnover charges</li>
 *   <li>sebiCharges — SEBI regulatory fee</li>
 *   <li>stampDuty — state stamp duty (charged on buy-side only)</li>
 *   <li>gst — 18% GST on (brokerage + exchangeCharges + sebiCharges)</li>
 * </ul>
 */
@Value
@Builder
public class ChargeBreakdown {

    BigDecimal brokerage;
    BigDecimal stt;
    BigDecimal exchangeCharges;
    BigDecimal sebiCharges;
    BigDecimal stampDuty;
    BigDecimal gst;

    public BigDecimal getTotal() {
        return brokerage
                .add(stt)
                .add(exchangeCharges)
                .add(sebiCharges)
                .add(stampDuty)
                .add(gst);
    }

    public static ChargeBreakdown zero() {
        return ChargeBreakdown.builder()
                .brokerage(BigDecimal.ZERO)
                .stt(BigDecimal.ZERO)
                .exchangeCharges(BigDecimal.ZERO)
                .sebiCharges(BigDecimal.ZERO)
                .stampDuty(BigDecimal.ZERO)
                .gst(BigDecimal.ZERO)
                .build();
    }
}
