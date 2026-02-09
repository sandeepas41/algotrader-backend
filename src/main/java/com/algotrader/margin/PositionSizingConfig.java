package com.algotrader.margin;

import com.algotrader.domain.enums.PositionSizingType;
import java.math.BigDecimal;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for position sizing, loaded from application.properties.
 *
 * <p>Properties prefix: {@code margin.position-sizing.*}. All sizer implementations
 * read their parameters from this shared config bean.
 *
 * <p>Defaults:
 * <ul>
 *   <li>defaultType: FIXED_LOTS (safest for initial setup)</li>
 *   <li>fixedLots: 1</li>
 *   <li>capitalPercentage: 5.0 (allocate 5% of capital per trade)</li>
 *   <li>riskPercentage: 2.0 (risk max 2% of capital per trade)</li>
 *   <li>maxLots: 10 (hard cap)</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "margin.position-sizing")
public class PositionSizingConfig {

    private PositionSizingType defaultType = PositionSizingType.FIXED_LOTS;
    private int fixedLots = 1;
    private BigDecimal capitalPercentage = new BigDecimal("5.0");
    private BigDecimal riskPercentage = new BigDecimal("2.0");
    private int maxLots = 10;
}
