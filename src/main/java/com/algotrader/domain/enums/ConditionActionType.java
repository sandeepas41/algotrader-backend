package com.algotrader.domain.enums;

/**
 * Action to take when a condition rule is triggered.
 *
 * <p>DEPLOY_STRATEGY creates a new strategy instance in CREATED state.
 * ARM_STRATEGY creates and immediately arms the strategy for live evaluation.
 * ALERT_ONLY sends a notification without deploying anything — useful for
 * monitoring conditions before committing to auto-deployment.
 */
public enum ConditionActionType {
    /** Auto-deploy a strategy in CREATED state. */
    DEPLOY_STRATEGY,
    /** Deploy and immediately arm the strategy. */
    ARM_STRATEGY,
    /** Only send an alert notification, no deployment. */
    ALERT_ONLY
}
