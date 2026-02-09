package com.algotrader.calendar;

/**
 * Classifies the type of holiday on the NSE trading calendar.
 *
 * <p>FULL_HOLIDAY means no trading at all. MUHURAT_TRADING (Diwali) is a special
 * 1-hour evening session that counts as a trading day but with different hours.
 */
public enum HolidayType {

    /** Full day holiday — no trading. */
    FULL_HOLIDAY,

    /** Special Muhurat trading session on Diwali (usually ~1 hour in the evening). */
    MUHURAT_TRADING
}
