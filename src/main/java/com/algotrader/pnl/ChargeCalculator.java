package com.algotrader.pnl;

import com.algotrader.domain.enums.OrderSide;
import com.algotrader.domain.vo.ChargeBreakdown;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Calculates all Indian market transaction charges for F&O trades on NSE via Zerodha.
 *
 * <p>Zerodha uses flat-fee brokerage (Rs 20/order) for F&O. Regulatory charges are:
 * <ul>
 *   <li><b>STT:</b> Options: 0.0625% sell-side premium only; Futures: 0.01% sell-side</li>
 *   <li><b>Exchange charges:</b> Options: 0.053% total turnover; Futures: 0.0019%</li>
 *   <li><b>SEBI:</b> Rs 10 per crore of total turnover</li>
 *   <li><b>Stamp duty:</b> Options: 0.003% buy-side only; Futures: 0.002% buy-side</li>
 *   <li><b>GST:</b> 18% on (brokerage + exchange charges + SEBI charges)</li>
 * </ul>
 *
 * <p>All rates are as of 2024 and can be updated via constants. The service returns
 * an immutable {@link ChargeBreakdown} value object for each calculation.
 *
 * <p>Used by PnLCalculationService for realized P&L, and by the order preview endpoint
 * for pre-trade charge estimation.
 */
@Service
public class ChargeCalculator {

    private static final Logger log = LoggerFactory.getLogger(ChargeCalculator.class);

    // Brokerage: Rs 20 per executed order (Zerodha flat rate for F&O)
    private static final BigDecimal BROKERAGE_PER_ORDER = new BigDecimal("20.00");

    // STT (Securities Transaction Tax)
    // Options: 0.0625% on sell-side premium only
    private static final BigDecimal STT_OPTIONS_SELL = new BigDecimal("0.000625");
    // Futures: 0.01% on sell-side
    private static final BigDecimal STT_FUTURES_SELL = new BigDecimal("0.0001");

    // Exchange transaction charges (NSE)
    // Options: 0.053% of turnover
    private static final BigDecimal EXCHANGE_OPTIONS = new BigDecimal("0.00053");
    // Futures: 0.0019% of turnover
    private static final BigDecimal EXCHANGE_FUTURES = new BigDecimal("0.000019");

    // SEBI charges: Rs 10 per crore of turnover
    private static final BigDecimal SEBI_CHARGE_RATE = new BigDecimal("10");
    private static final BigDecimal ONE_CRORE = new BigDecimal("10000000");

    // Stamp duty (buyer side only)
    // Options: 0.003% on buy-side
    private static final BigDecimal STAMP_DUTY_OPTIONS = new BigDecimal("0.00003");
    // Futures: 0.002% on buy-side
    private static final BigDecimal STAMP_DUTY_FUTURES = new BigDecimal("0.00002");

    // GST: 18% on (brokerage + exchange charges + SEBI charges)
    private static final BigDecimal GST_RATE = new BigDecimal("0.18");

    /**
     * Calculates charges for an options trade with separate buy/sell quantities and premiums.
     *
     * @param buyPremium    average buy-side premium per unit
     * @param sellPremium   average sell-side premium per unit
     * @param buyQuantity   total buy quantity (always positive)
     * @param sellQuantity  total sell quantity (always positive)
     * @param numberOfOrders number of executed orders (for brokerage)
     * @return immutable charge breakdown
     */
    public ChargeBreakdown calculateOptionsCharges(
            BigDecimal buyPremium, BigDecimal sellPremium, int buyQuantity, int sellQuantity, int numberOfOrders) {
        BigDecimal buyTurnover = buyPremium.multiply(BigDecimal.valueOf(buyQuantity));
        BigDecimal sellTurnover = sellPremium.multiply(BigDecimal.valueOf(sellQuantity));
        BigDecimal totalTurnover = buyTurnover.add(sellTurnover);

        BigDecimal brokerage = BROKERAGE_PER_ORDER.multiply(BigDecimal.valueOf(numberOfOrders));

        // STT: on sell-side premium only for options
        BigDecimal stt = sellTurnover.multiply(STT_OPTIONS_SELL).setScale(2, RoundingMode.HALF_UP);

        // Exchange charges: on total turnover
        BigDecimal exchangeCharges = totalTurnover.multiply(EXCHANGE_OPTIONS).setScale(2, RoundingMode.HALF_UP);

        // SEBI charges: Rs 10 per crore of turnover
        BigDecimal sebiCharges = totalTurnover
                .divide(ONE_CRORE, 10, RoundingMode.HALF_UP)
                .multiply(SEBI_CHARGE_RATE)
                .setScale(2, RoundingMode.HALF_UP);

        // Stamp duty: on buy-side only
        BigDecimal stampDuty = buyTurnover.multiply(STAMP_DUTY_OPTIONS).setScale(2, RoundingMode.HALF_UP);

        // GST: 18% on (brokerage + exchange charges + SEBI charges)
        BigDecimal gstBase = brokerage.add(exchangeCharges).add(sebiCharges);
        BigDecimal gst = gstBase.multiply(GST_RATE).setScale(2, RoundingMode.HALF_UP);

        return ChargeBreakdown.builder()
                .brokerage(brokerage)
                .stt(stt)
                .exchangeCharges(exchangeCharges)
                .sebiCharges(sebiCharges)
                .stampDuty(stampDuty)
                .gst(gst)
                .build();
    }

    /**
     * Calculates charges for a futures trade.
     *
     * @param buyPrice       buy price per unit
     * @param sellPrice      sell price per unit
     * @param quantity       quantity traded (always positive)
     * @param numberOfOrders number of executed orders
     * @return immutable charge breakdown
     */
    public ChargeBreakdown calculateFuturesCharges(
            BigDecimal buyPrice, BigDecimal sellPrice, int quantity, int numberOfOrders) {
        BigDecimal buyTurnover = buyPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal sellTurnover = sellPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal totalTurnover = buyTurnover.add(sellTurnover);

        BigDecimal brokerage = BROKERAGE_PER_ORDER.multiply(BigDecimal.valueOf(numberOfOrders));

        BigDecimal stt = sellTurnover.multiply(STT_FUTURES_SELL).setScale(2, RoundingMode.HALF_UP);

        BigDecimal exchangeCharges = totalTurnover.multiply(EXCHANGE_FUTURES).setScale(2, RoundingMode.HALF_UP);

        BigDecimal sebiCharges = totalTurnover
                .divide(ONE_CRORE, 10, RoundingMode.HALF_UP)
                .multiply(SEBI_CHARGE_RATE)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal stampDuty = buyTurnover.multiply(STAMP_DUTY_FUTURES).setScale(2, RoundingMode.HALF_UP);

        BigDecimal gstBase = brokerage.add(exchangeCharges).add(sebiCharges);
        BigDecimal gst = gstBase.multiply(GST_RATE).setScale(2, RoundingMode.HALF_UP);

        return ChargeBreakdown.builder()
                .brokerage(brokerage)
                .stt(stt)
                .exchangeCharges(exchangeCharges)
                .sebiCharges(sebiCharges)
                .stampDuty(stampDuty)
                .gst(gst)
                .build();
    }

    /**
     * Quick single-order charge estimate for pre-trade display.
     * Uses sell-side assumptions for STT (conservative estimate).
     *
     * @param premium  option premium per unit
     * @param quantity quantity
     * @param side     BUY or SELL (affects STT and stamp duty)
     * @return estimated total charges
     */
    public BigDecimal estimateOptionsCharges(BigDecimal premium, int quantity, OrderSide side) {
        BigDecimal turnover = premium.multiply(BigDecimal.valueOf(quantity));

        BigDecimal brokerage = BROKERAGE_PER_ORDER;

        // STT only on sell-side
        BigDecimal stt = side == OrderSide.SELL ? turnover.multiply(STT_OPTIONS_SELL) : BigDecimal.ZERO;

        BigDecimal exchange = turnover.multiply(EXCHANGE_OPTIONS);

        BigDecimal sebi = turnover.divide(ONE_CRORE, 10, RoundingMode.HALF_UP).multiply(SEBI_CHARGE_RATE);

        // Stamp duty only on buy-side
        BigDecimal stamp = side == OrderSide.BUY ? turnover.multiply(STAMP_DUTY_OPTIONS) : BigDecimal.ZERO;

        BigDecimal gstBase = brokerage.add(exchange).add(sebi);
        BigDecimal gst = gstBase.multiply(GST_RATE);

        return brokerage.add(stt).add(exchange).add(sebi).add(stamp).add(gst).setScale(2, RoundingMode.HALF_UP);
    }
}
