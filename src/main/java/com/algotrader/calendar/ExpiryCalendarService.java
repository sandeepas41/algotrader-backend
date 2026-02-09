package com.algotrader.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Calculates weekly and monthly expiry dates for NSE derivatives.
 *
 * <p>NSE weekly expiries fall on Thursdays, and monthly expiries on the last Thursday
 * of each month. If an expiry Thursday falls on a holiday, the expiry is moved to
 * the previous trading day (this matches NSE's actual behavior).
 *
 * <p>This service is used by:
 * <ul>
 *   <li>GreeksCalculator — to compute time-to-expiry in trading days</li>
 *   <li>OptionChainService — to determine which expiry to show</li>
 *   <li>StrategyEngine — for expiry-based entry/exit conditions</li>
 * </ul>
 */
@Service
public class ExpiryCalendarService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryCalendarService.class);

    private final TradingCalendarService tradingCalendarService;

    public ExpiryCalendarService(TradingCalendarService tradingCalendarService) {
        this.tradingCalendarService = tradingCalendarService;
    }

    /**
     * Gets the current week's expiry date (Thursday, or previous trading day if holiday).
     * If today is past this week's Thursday, returns the next Thursday.
     */
    public LocalDate getCurrentWeeklyExpiry() {
        return getCurrentWeeklyExpiry(LocalDate.now());
    }

    /**
     * Gets the weekly expiry relative to the given reference date.
     */
    public LocalDate getCurrentWeeklyExpiry(LocalDate referenceDate) {
        LocalDate thursday;
        // If today is Friday, Saturday, or Sunday, use next week's Thursday
        if (referenceDate.getDayOfWeek().getValue() > DayOfWeek.THURSDAY.getValue()) {
            thursday = referenceDate.with(TemporalAdjusters.next(DayOfWeek.THURSDAY));
        } else {
            thursday = referenceDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY));
        }
        return adjustForHoliday(thursday);
    }

    /**
     * Gets the next week's expiry date (the Thursday after the current weekly expiry).
     */
    public LocalDate getNextWeeklyExpiry() {
        return getNextWeeklyExpiry(LocalDate.now());
    }

    public LocalDate getNextWeeklyExpiry(LocalDate referenceDate) {
        LocalDate currentExpiry = getCurrentWeeklyExpiry(referenceDate);
        // Move to the next Thursday after the current expiry
        LocalDate nextThursday = currentExpiry.plusDays(1);
        nextThursday = nextThursday.with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY));
        return adjustForHoliday(nextThursday);
    }

    /**
     * Gets the current month's expiry (last Thursday of the month).
     * If today is past this month's last Thursday, returns next month's.
     */
    public LocalDate getCurrentMonthlyExpiry() {
        return getCurrentMonthlyExpiry(LocalDate.now());
    }

    public LocalDate getCurrentMonthlyExpiry(LocalDate referenceDate) {
        LocalDate lastThursday = referenceDate.with(TemporalAdjusters.lastInMonth(DayOfWeek.THURSDAY));

        // If we've passed this month's expiry, get next month's
        if (referenceDate.isAfter(lastThursday)) {
            lastThursday = referenceDate
                    .plusMonths(1)
                    .with(TemporalAdjusters.firstDayOfMonth())
                    .with(TemporalAdjusters.lastInMonth(DayOfWeek.THURSDAY));
        }

        return adjustForHoliday(lastThursday);
    }

    /**
     * Gets the next month's expiry (last Thursday of the month after the current monthly expiry).
     */
    public LocalDate getNextMonthlyExpiry() {
        return getNextMonthlyExpiry(LocalDate.now());
    }

    public LocalDate getNextMonthlyExpiry(LocalDate referenceDate) {
        LocalDate currentMonthly = getCurrentMonthlyExpiry(referenceDate);
        // Move to next month's last Thursday
        LocalDate nextMonth = currentMonthly.plusMonths(1);
        LocalDate lastThursday = nextMonth
                .with(TemporalAdjusters.firstDayOfMonth())
                .with(TemporalAdjusters.lastInMonth(DayOfWeek.THURSDAY));
        return adjustForHoliday(lastThursday);
    }

    /**
     * Gets all weekly expiry dates between two dates (inclusive).
     */
    public List<LocalDate> getExpiryDatesBetween(LocalDate from, LocalDate to) {
        List<LocalDate> expiries = new ArrayList<>();
        LocalDate current = from;

        while (!current.isAfter(to)) {
            if (current.getDayOfWeek() == DayOfWeek.THURSDAY) {
                LocalDate adjusted = adjustForHoliday(current);
                if (!adjusted.isBefore(from) && !adjusted.isAfter(to) && !expiries.contains(adjusted)) {
                    expiries.add(adjusted);
                }
            }
            current = current.plusDays(1);
        }

        return expiries;
    }

    /**
     * Calculates the number of trading days from a given date to the expiry date.
     * Trading days exclude weekends and holidays.
     */
    public int getTradingDaysToExpiry(LocalDate expiry) {
        return getTradingDaysToExpiry(LocalDate.now(), expiry);
    }

    public int getTradingDaysToExpiry(LocalDate from, LocalDate expiry) {
        LocalDate current = from;
        int tradingDays = 0;

        while (current.isBefore(expiry)) {
            current = current.plusDays(1);
            if (tradingCalendarService.isTradingDay(current)) {
                tradingDays++;
            }
        }

        return tradingDays;
    }

    /** Returns true if today is a weekly expiry day. */
    public boolean isExpiryDay() {
        return isExpiryDay(LocalDate.now());
    }

    /** Returns true if the given date is a weekly expiry day. */
    public boolean isExpiryDay(LocalDate date) {
        return date.equals(getCurrentWeeklyExpiry(date));
    }

    /**
     * If the expiry falls on a holiday, move to the previous trading day.
     * This matches NSE's actual behavior for holiday-adjusted expiries.
     */
    private LocalDate adjustForHoliday(LocalDate date) {
        while (tradingCalendarService.isHoliday(date)) {
            date = date.minusDays(1);
        }
        return date;
    }
}
