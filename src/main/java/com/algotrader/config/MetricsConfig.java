package com.algotrader.config;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer metrics configuration for the AlgoTrader platform.
 *
 * <p>Registers common tags applied to all metrics (application name) so that
 * CloudWatch dimensions are consistent across all custom and auto-configured
 * metrics. The actual custom metric definitions live in {@link
 * com.algotrader.observability.CustomMetricsService}.
 *
 * <p>CloudWatch free tier allows the first 10 custom metrics, which aligns with
 * our 7 custom metrics: orders.placed, orders.failed, daily.pnl, active.strategies,
 * kite.session.state, tick.latency, reconciliation.mismatches.
 */
@Configuration
public class MetricsConfig {

    private final MeterRegistry meterRegistry;

    public MetricsConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void configureCommonTags() {
        meterRegistry.config().commonTags("application", "algotrader");
    }
}
