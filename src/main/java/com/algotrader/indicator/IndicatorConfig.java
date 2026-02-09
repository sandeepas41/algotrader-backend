package com.algotrader.indicator;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Root configuration for the indicator subsystem, bound from application.yml
 * under the {@code indicators} prefix.
 *
 * <p>Contains the list of instruments to track with their bar durations and
 * indicator definitions. Supports hot reload if combined with
 * {@code @RefreshScope} in the future.
 */
@Data
@ConfigurationProperties(prefix = "indicators")
public class IndicatorConfig {

    /** Whether the indicator subsystem is enabled. Defaults to true. */
    private boolean enabled = true;

    /** List of instruments with their indicator configurations. */
    private List<InstrumentIndicatorConfig> instruments = new ArrayList<>();
}
