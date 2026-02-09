package com.algotrader.margin;

import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.domain.model.AccountMargin;
import com.algotrader.event.RiskEvent;
import com.algotrader.event.RiskEventType;
import com.algotrader.event.RiskLevel;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Monitors margin utilization every 30 seconds and publishes risk events at thresholds.
 *
 * <p>Two threshold levels:
 * <ul>
 *   <li><b>WARNING (80%):</b> Alerts the trader that margin is getting tight. No automatic
 *       action taken — the trader decides whether to reduce positions.</li>
 *   <li><b>CRITICAL (90%):</b> Margin is dangerously high. Publishes a CRITICAL risk event
 *       that may trigger automatic position reduction or kill switch.</li>
 * </ul>
 *
 * <p>Each threshold fires only once (deduplication via AtomicBoolean flags). When utilization
 * drops below the warning threshold, both flags are reset so they can fire again on the
 * next breach. This prevents alert spam during sustained high utilization.
 *
 * <p>Only runs during market hours (NORMAL phase) to avoid unnecessary API calls when
 * no trading is possible.
 */
@Service
public class MarginMonitorService {

    private static final Logger log = LoggerFactory.getLogger(MarginMonitorService.class);

    private static final BigDecimal WARNING_THRESHOLD = new BigDecimal("80");
    private static final BigDecimal CRITICAL_THRESHOLD = new BigDecimal("90");

    private final MarginService marginService;
    private final TradingCalendarService tradingCalendarService;
    private final ApplicationEventPublisher applicationEventPublisher;

    private final AtomicBoolean warningFired = new AtomicBoolean(false);
    private final AtomicBoolean criticalFired = new AtomicBoolean(false);

    public MarginMonitorService(
            MarginService marginService,
            TradingCalendarService tradingCalendarService,
            ApplicationEventPublisher applicationEventPublisher) {
        this.marginService = marginService;
        this.tradingCalendarService = tradingCalendarService;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Checks margin utilization every 30 seconds during market hours.
     * Publishes {@link RiskEvent} with {@link RiskEventType#MARGIN_UTILIZATION_HIGH}
     * when thresholds are breached.
     */
    @Scheduled(fixedRate = 30_000)
    public void checkMarginUtilization() {
        if (!tradingCalendarService.isMarketOpen()) {
            return;
        }

        checkMarginUtilization(marginService.getMargins());
    }

    /**
     * Checks the given margin snapshot against thresholds. Public for testability
     * since the test package differs from the source package.
     */
    public void checkMarginUtilization(AccountMargin margin) {
        try {
            BigDecimal utilization = margin.getUtilizationPercent();

            // Critical: >= 90% utilization
            if (utilization.compareTo(CRITICAL_THRESHOLD) >= 0) {
                if (!criticalFired.getAndSet(true)) {
                    log.error("CRITICAL: Margin utilization at {}%", utilization);
                    applicationEventPublisher.publishEvent(new RiskEvent(
                            this,
                            RiskEventType.MARGIN_UTILIZATION_HIGH,
                            RiskLevel.CRITICAL,
                            "Margin utilization CRITICAL at " + utilization + "%. Available: "
                                    + margin.getAvailableMargin() + ", Used: " + margin.getUsedMargin(),
                            Map.of(
                                    "utilization",
                                    utilization,
                                    "threshold",
                                    CRITICAL_THRESHOLD,
                                    "availableMargin",
                                    margin.getAvailableMargin(),
                                    "usedMargin",
                                    margin.getUsedMargin())));
                }
            }
            // Warning: >= 80% utilization
            else if (utilization.compareTo(WARNING_THRESHOLD) >= 0) {
                if (!warningFired.getAndSet(true)) {
                    log.warn("WARNING: Margin utilization at {}%", utilization);
                    applicationEventPublisher.publishEvent(new RiskEvent(
                            this,
                            RiskEventType.MARGIN_UTILIZATION_HIGH,
                            RiskLevel.WARNING,
                            "Margin utilization at " + utilization + "%. Available: " + margin.getAvailableMargin(),
                            Map.of(
                                    "utilization",
                                    utilization,
                                    "threshold",
                                    WARNING_THRESHOLD,
                                    "availableMargin",
                                    margin.getAvailableMargin())));
                }
            }
            // Below thresholds: reset flags so alerts can fire again
            else {
                warningFired.set(false);
                criticalFired.set(false);
            }

        } catch (Exception e) {
            log.warn("Failed to check margin utilization", e);
        }
    }

    /**
     * Resets alert deduplication flags. Called on daily reset or when the trader
     * acknowledges the alert.
     */
    public void resetAlertFlags() {
        warningFired.set(false);
        criticalFired.set(false);
    }
}
