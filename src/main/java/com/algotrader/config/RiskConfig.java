package com.algotrader.config;

import com.algotrader.risk.RiskLimits;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the global {@link RiskLimits} bean from application.properties.
 *
 * <p>All risk limits default to null (disabled) so the system runs permissively
 * until explicitly configured. Traders set production limits via the Risk API
 * or application.properties. Null = check skipped.
 *
 * <p>Properties prefix: {@code algotrader.risk.*}
 */
@Configuration
public class RiskConfig {

    @Bean
    public RiskLimits riskLimits(
            @Value("${algotrader.risk.max-loss-per-position:#{null}}") BigDecimal maxLossPerPosition,
            @Value("${algotrader.risk.max-profit-per-position:#{null}}") BigDecimal maxProfitPerPosition,
            @Value("${algotrader.risk.max-lots-per-position:#{null}}") Integer maxLotsPerPosition,
            @Value("${algotrader.risk.max-position-value:#{null}}") BigDecimal maxPositionValue,
            @Value("${algotrader.risk.daily-loss-limit:#{null}}") BigDecimal dailyLossLimit,
            @Value("${algotrader.risk.daily-loss-warning-threshold:#{null}}") BigDecimal dailyLossWarningThreshold,
            @Value("${algotrader.risk.max-margin-utilization:#{null}}") BigDecimal maxMarginUtilization,
            @Value("${algotrader.risk.max-open-positions:#{null}}") Integer maxOpenPositions,
            @Value("${algotrader.risk.max-open-orders:#{null}}") Integer maxOpenOrders,
            @Value("${algotrader.risk.max-active-strategies:#{null}}") Integer maxActiveStrategies,
            @Value("${algotrader.risk.max-loss-per-strategy:#{null}}") BigDecimal maxLossPerStrategy,
            @Value("${algotrader.risk.max-legs-per-strategy:#{null}}") Integer maxLegsPerStrategy) {
        return RiskLimits.builder()
                .maxLossPerPosition(maxLossPerPosition)
                .maxProfitPerPosition(maxProfitPerPosition)
                .maxLotsPerPosition(maxLotsPerPosition)
                .maxPositionValue(maxPositionValue)
                .dailyLossLimit(dailyLossLimit)
                .dailyLossWarningThreshold(dailyLossWarningThreshold)
                .maxMarginUtilization(maxMarginUtilization)
                .maxOpenPositions(maxOpenPositions)
                .maxOpenOrders(maxOpenOrders)
                .maxActiveStrategies(maxActiveStrategies)
                .maxLossPerStrategy(maxLossPerStrategy)
                .maxLegsPerStrategy(maxLegsPerStrategy)
                .build();
    }
}
