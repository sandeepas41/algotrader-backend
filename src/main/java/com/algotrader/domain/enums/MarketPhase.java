package com.algotrader.domain.enums;

import java.time.LocalTime;

/**
 * NSE market phases based on time of day, with their time boundaries.
 *
 * <pre>
 * 09:00-09:08  PRE_OPEN                — Indicative prices, orders accepted
 * 09:08-09:15  PRE_OPEN_ORDER_MATCHING — Orders matched, no new orders
 * 09:15-15:30  NORMAL                  — Regular trading session
 * 15:30-15:40  CLOSING                 — Closing auction
 * 15:40-16:00  POST_CLOSE              — Limited trading at closing price
 * 16:00-09:00  CLOSED                  — Market closed
 * </pre>
 *
 * <p>Strategies should only evaluate entry/exit during NORMAL phase.
 * PRE_OPEN allows order placement but not matching. The TradingCalendarService
 * uses these time boundaries to detect phase transitions.
 */
public enum MarketPhase {
    PRE_OPEN(LocalTime.of(9, 0), LocalTime.of(9, 8), "Pre-open session, orders accepted but not matched"),
    PRE_OPEN_ORDER_MATCHING(LocalTime.of(9, 8), LocalTime.of(9, 15), "Pre-open order matching, no new orders accepted"),
    NORMAL(LocalTime.of(9, 15), LocalTime.of(15, 30), "Normal trading session"),
    CLOSING(LocalTime.of(15, 30), LocalTime.of(15, 40), "Closing session, closing price determination"),
    POST_CLOSE(LocalTime.of(15, 40), LocalTime.of(16, 0), "Post-close session, limited trading at closing price"),
    CLOSED(LocalTime.of(16, 0), LocalTime.of(9, 0), "Market closed");

    private final LocalTime startTime;
    private final LocalTime endTime;
    private final String description;

    MarketPhase(LocalTime startTime, LocalTime endTime, String description) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.description = description;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getDescription() {
        return description;
    }
}
