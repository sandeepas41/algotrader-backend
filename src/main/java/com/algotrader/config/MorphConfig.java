package com.algotrader.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the morph service.
 *
 * <p>Controls safety limits (max legs to close/open per morph),
 * order timeouts, and whether morphs require UI confirmation.
 * Properties are read from the {@code algotrader.morph} prefix.
 */
@Configuration
@ConfigurationProperties(prefix = "algotrader.morph")
@Getter
@Setter
public class MorphConfig {

    /** Whether morphing is enabled at all. Defaults to true. */
    private boolean enabled = true;

    /** Safety limit on legs closed in a single morph. */
    private int maxLegsToClose = 10;

    /** Safety limit on new legs opened in a single morph. */
    private int maxLegsToOpen = 10;

    /** Timeout in seconds for close order fills. */
    private int closeOrderTimeoutSeconds = 30;

    /** Timeout in seconds for open order fills. */
    private int openOrderTimeoutSeconds = 30;

    /** If true, morph preview must be confirmed via UI before execution. */
    private boolean requireConfirmation = true;
}
