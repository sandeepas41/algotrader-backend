package com.algotrader.api.dto.request;

import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO for parsing the FE-sent strategy config JSON string.
 *
 * <p>The FE sends the config field as a stringified JSON containing leg definitions,
 * rules, lot count, and trading mode. This DTO is used by {@link com.algotrader.api.controller.StrategyController}
 * to extract structured data via {@link com.algotrader.mapper.JsonHelper#fromJson}.
 *
 * <p>Example FE payload:
 * <pre>
 * {"legs":[{"optionType":"CE","strikeType":"FIXED","fixedStrike":25850,"quantity":1}],
 *  "rules":[],"lots":2,"tradingMode":"LIVE"}
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class DeployConfigPayload {

    private List<LegPayload> legs;
    private List<Object> rules;
    private int lots = 1;
    private String tradingMode;

    /**
     * A single leg definition from the FE strategy builder.
     * Quantity is signed: positive = BUY, negative = SELL.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class LegPayload {
        private String optionType;
        private String strikeType;
        private BigDecimal fixedStrike;
        private int strikeOffset;
        /** Signed: +1 = BUY 1 lot, -1 = SELL 1 lot */
        private int quantity;
    }
}
