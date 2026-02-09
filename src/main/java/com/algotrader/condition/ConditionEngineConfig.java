package com.algotrader.condition;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the ConditionEngine under the {@code condition-engine} prefix.
 *
 * <p>Controls global behavior of condition evaluation:
 * <ul>
 *   <li>{@code enabled} -- master toggle for the engine</li>
 *   <li>{@code tickEvaluation} -- whether tick-level rules are evaluated (disable to reduce CPU)</li>
 *   <li>{@code intervalCheckMs} -- base interval for scheduled evaluations (default 60s)</li>
 *   <li>{@code defaultCooldownMinutes} -- default cooldown when not specified per rule</li>
 *   <li>{@code maxActiveRules} -- cap on active rules to prevent overload</li>
 * </ul>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "condition-engine")
public class ConditionEngineConfig {

    private boolean enabled = true;
    private boolean tickEvaluation = true;
    private long intervalCheckMs = 60000;
    private int defaultCooldownMinutes = 30;
    private int maxActiveRules = 50;
}
