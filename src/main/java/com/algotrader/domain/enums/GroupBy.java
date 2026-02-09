package com.algotrader.domain.enums;

/**
 * Time grouping for P&L reports: daily, weekly, or monthly aggregation.
 * Used by ReportingService to bucket daily P&L records into summary periods.
 */
public enum GroupBy {
    DAILY,
    WEEKLY,
    MONTHLY
}
