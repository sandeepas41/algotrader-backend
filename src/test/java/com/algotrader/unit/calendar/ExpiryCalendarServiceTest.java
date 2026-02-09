package com.algotrader.unit.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.algotrader.calendar.ExpiryCalendarService;
import com.algotrader.calendar.HolidayCalendarConfig;
import com.algotrader.calendar.HolidayType;
import com.algotrader.calendar.TradingCalendarService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.support.StaticApplicationContext;

/**
 * Unit tests for ExpiryCalendarService covering weekly and monthly expiry
 * calculations, holiday adjustments, and trading day counts.
 */
class ExpiryCalendarServiceTest {

    private HolidayCalendarConfig holidayCalendarConfig;
    private TradingCalendarService tradingCalendarService;
    private ExpiryCalendarService expiryCalendarService;

    @BeforeEach
    void setUp() {
        holidayCalendarConfig = new HolidayCalendarConfig();
        holidayCalendarConfig.setExchange("NSE");
        holidayCalendarConfig.setTimezone("Asia/Kolkata");
        holidayCalendarConfig.setHolidays(new ArrayList<>());

        ApplicationEventPublisher applicationEventPublisher = new StaticApplicationContext();
        tradingCalendarService = new TradingCalendarService(holidayCalendarConfig, applicationEventPublisher);
        expiryCalendarService = new ExpiryCalendarService(tradingCalendarService);
    }

    @Nested
    @DisplayName("Weekly Expiry Calculation")
    class WeeklyExpiry {

        @Test
        @DisplayName("On Monday, current weekly expiry should be this Thursday")
        void mondayExpiryIsThisThursday() {
            // Monday June 9, 2025 -> expiry should be Thursday June 12
            LocalDate monday = LocalDate.of(2025, 6, 9);
            LocalDate expiry = expiryCalendarService.getCurrentWeeklyExpiry(monday);
            assertThat(expiry).isEqualTo(LocalDate.of(2025, 6, 12));
            assertThat(expiry.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
        }

        @Test
        @DisplayName("On Thursday, current weekly expiry should be this Thursday")
        void thursdayExpiryIsToday() {
            // Thursday June 12, 2025
            LocalDate thursday = LocalDate.of(2025, 6, 12);
            LocalDate expiry = expiryCalendarService.getCurrentWeeklyExpiry(thursday);
            assertThat(expiry).isEqualTo(LocalDate.of(2025, 6, 12));
        }

        @Test
        @DisplayName("On Friday, current weekly expiry should be next Thursday")
        void fridayExpiryIsNextThursday() {
            // Friday June 13, 2025 -> expiry should be Thursday June 19
            LocalDate friday = LocalDate.of(2025, 6, 13);
            LocalDate expiry = expiryCalendarService.getCurrentWeeklyExpiry(friday);
            assertThat(expiry).isEqualTo(LocalDate.of(2025, 6, 19));
        }

        @Test
        @DisplayName("On Saturday, current weekly expiry should be next Thursday")
        void saturdayExpiryIsNextThursday() {
            // Saturday June 14, 2025 -> next Thursday is June 19
            LocalDate saturday = LocalDate.of(2025, 6, 14);
            LocalDate expiry = expiryCalendarService.getCurrentWeeklyExpiry(saturday);
            assertThat(expiry).isEqualTo(LocalDate.of(2025, 6, 19));
        }

        @Test
        @DisplayName("On Sunday, current weekly expiry should be next Thursday")
        void sundayExpiryIsNextThursday() {
            // Sunday June 15, 2025 -> next Thursday is June 19
            LocalDate sunday = LocalDate.of(2025, 6, 15);
            LocalDate expiry = expiryCalendarService.getCurrentWeeklyExpiry(sunday);
            assertThat(expiry).isEqualTo(LocalDate.of(2025, 6, 19));
        }

        @Test
        @DisplayName("On Wednesday, current weekly expiry should be this Thursday")
        void wednesdayExpiryIsThisThursday() {
            // Wednesday June 11, 2025 -> expiry should be Thursday June 12
            LocalDate wednesday = LocalDate.of(2025, 6, 11);
            LocalDate expiry = expiryCalendarService.getCurrentWeeklyExpiry(wednesday);
            assertThat(expiry).isEqualTo(LocalDate.of(2025, 6, 12));
        }
    }

    @Nested
    @DisplayName("Next Weekly Expiry")
    class NextWeeklyExpiry {

        @Test
        @DisplayName("Next weekly expiry from Monday is the Thursday after this week's")
        void nextWeeklyFromMonday() {
            // Monday June 9 -> current is June 12, next is June 19
            LocalDate monday = LocalDate.of(2025, 6, 9);
            LocalDate nextExpiry = expiryCalendarService.getNextWeeklyExpiry(monday);
            assertThat(nextExpiry).isEqualTo(LocalDate.of(2025, 6, 19));
        }

        @Test
        @DisplayName("Next weekly expiry from Thursday is the following Thursday")
        void nextWeeklyFromThursday() {
            // Thursday June 12 -> current is June 12, next is June 19
            LocalDate thursday = LocalDate.of(2025, 6, 12);
            LocalDate nextExpiry = expiryCalendarService.getNextWeeklyExpiry(thursday);
            assertThat(nextExpiry).isEqualTo(LocalDate.of(2025, 6, 19));
        }
    }

    @Nested
    @DisplayName("Monthly Expiry Calculation")
    class MonthlyExpiry {

        @Test
        @DisplayName("Before last Thursday, monthly expiry is last Thursday of current month")
        void beforeLastThursday() {
            // June 1, 2025 -> last Thursday of June is June 26
            LocalDate june1 = LocalDate.of(2025, 6, 1);
            LocalDate expiry = expiryCalendarService.getCurrentMonthlyExpiry(june1);
            assertThat(expiry).isEqualTo(LocalDate.of(2025, 6, 26));
            assertThat(expiry.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
        }

        @Test
        @DisplayName("On last Thursday, monthly expiry is today")
        void onLastThursday() {
            // June 26, 2025 is last Thursday of June
            LocalDate lastThursday = LocalDate.of(2025, 6, 26);
            LocalDate expiry = expiryCalendarService.getCurrentMonthlyExpiry(lastThursday);
            assertThat(expiry).isEqualTo(LocalDate.of(2025, 6, 26));
        }

        @Test
        @DisplayName("After last Thursday, monthly expiry is next month's last Thursday")
        void afterLastThursday() {
            // June 27, 2025 (Friday after last Thursday) -> next month's last Thursday = July 31
            LocalDate june27 = LocalDate.of(2025, 6, 27);
            LocalDate expiry = expiryCalendarService.getCurrentMonthlyExpiry(june27);
            assertThat(expiry).isEqualTo(LocalDate.of(2025, 7, 31));
            assertThat(expiry.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
        }

        @Test
        @DisplayName("Known monthly expiry dates for 2025")
        void knownMonthlyExpiries2025() {
            // Verify a few known 2025 monthly expiry dates
            assertThat(expiryCalendarService.getCurrentMonthlyExpiry(LocalDate.of(2025, 1, 1)))
                    .isEqualTo(LocalDate.of(2025, 1, 30)); // Jan last Thursday

            assertThat(expiryCalendarService.getCurrentMonthlyExpiry(LocalDate.of(2025, 3, 1)))
                    .isEqualTo(LocalDate.of(2025, 3, 27)); // Mar last Thursday

            assertThat(expiryCalendarService.getCurrentMonthlyExpiry(LocalDate.of(2025, 12, 1)))
                    .isEqualTo(LocalDate.of(2025, 12, 25)); // Dec last Thursday
        }
    }

    @Nested
    @DisplayName("Next Monthly Expiry")
    class NextMonthlyExpiry {

        @Test
        @DisplayName("Next monthly expiry from early June is July's last Thursday")
        void nextMonthlyFromJune() {
            // June 1, 2025 -> current monthly = June 26, next = July 31
            LocalDate june1 = LocalDate.of(2025, 6, 1);
            LocalDate nextExpiry = expiryCalendarService.getNextMonthlyExpiry(june1);
            assertThat(nextExpiry).isEqualTo(LocalDate.of(2025, 7, 31));
        }
    }

    @Nested
    @DisplayName("Holiday Adjustment")
    class HolidayAdjustment {

        @Test
        @DisplayName("Expiry on holiday shifts to previous trading day")
        void expiryShiftedOnHoliday() {
            // Make Thursday Aug 14, 2025 a holiday
            addHoliday(LocalDate.of(2025, 8, 14), "Test Holiday", HolidayType.FULL_HOLIDAY);

            // Monday Aug 11 -> weekly expiry would normally be Thursday Aug 14
            // But Aug 14 is a holiday, so should shift to Wednesday Aug 13
            LocalDate monday = LocalDate.of(2025, 8, 11);
            LocalDate expiry = expiryCalendarService.getCurrentWeeklyExpiry(monday);
            assertThat(expiry).isEqualTo(LocalDate.of(2025, 8, 13));
            assertThat(expiry.getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        }

        @Test
        @DisplayName("Expiry shifts across multiple holidays")
        void expiryShiftsMultipleHolidays() {
            // Make Thursday and Wednesday holidays
            addHoliday(LocalDate.of(2025, 8, 14), "Holiday 1", HolidayType.FULL_HOLIDAY);
            addHoliday(LocalDate.of(2025, 8, 13), "Holiday 2", HolidayType.FULL_HOLIDAY);

            // Expiry shifts from Thu Aug 14 -> Wed Aug 13 -> Tue Aug 12
            LocalDate monday = LocalDate.of(2025, 8, 11);
            LocalDate expiry = expiryCalendarService.getCurrentWeeklyExpiry(monday);
            assertThat(expiry).isEqualTo(LocalDate.of(2025, 8, 12));
            assertThat(expiry.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        }

        @Test
        @DisplayName("Monthly expiry shifts on holiday")
        void monthlyExpiryShiftsOnHoliday() {
            // Dec 25, 2025 is a Thursday and Christmas (holiday)
            addHoliday(LocalDate.of(2025, 12, 25), "Christmas", HolidayType.FULL_HOLIDAY);

            // Monthly expiry for December = last Thursday = Dec 25 -> shifts to Dec 24 (Wednesday)
            LocalDate expiry = expiryCalendarService.getCurrentMonthlyExpiry(LocalDate.of(2025, 12, 1));
            assertThat(expiry).isEqualTo(LocalDate.of(2025, 12, 24));
        }
    }

    @Nested
    @DisplayName("Expiry Dates Between Range")
    class ExpiryDatesBetween {

        @Test
        @DisplayName("Gets all weekly expiries in a month")
        void weeklyExpiriesInJune() {
            LocalDate from = LocalDate.of(2025, 6, 1);
            LocalDate to = LocalDate.of(2025, 6, 30);
            List<LocalDate> expiries = expiryCalendarService.getExpiryDatesBetween(from, to);

            // June 2025 has Thursdays on: 5, 12, 19, 26
            assertThat(expiries)
                    .containsExactly(
                            LocalDate.of(2025, 6, 5),
                            LocalDate.of(2025, 6, 12),
                            LocalDate.of(2025, 6, 19),
                            LocalDate.of(2025, 6, 26));
        }

        @Test
        @DisplayName("Holiday-adjusted expiry dates are included")
        void holidayAdjustedExpiriesIncluded() {
            // Make June 12 (Thursday) a holiday
            addHoliday(LocalDate.of(2025, 6, 12), "Test Holiday", HolidayType.FULL_HOLIDAY);

            LocalDate from = LocalDate.of(2025, 6, 9);
            LocalDate to = LocalDate.of(2025, 6, 15);
            List<LocalDate> expiries = expiryCalendarService.getExpiryDatesBetween(from, to);

            // Thu Jun 12 is holiday -> adjusted to Wed Jun 11
            assertThat(expiries).containsExactly(LocalDate.of(2025, 6, 11));
        }

        @Test
        @DisplayName("Empty range returns empty list")
        void emptyRange() {
            // Range with no Thursdays
            LocalDate from = LocalDate.of(2025, 6, 6); // Friday
            LocalDate to = LocalDate.of(2025, 6, 8); // Sunday
            List<LocalDate> expiries = expiryCalendarService.getExpiryDatesBetween(from, to);
            assertThat(expiries).isEmpty();
        }
    }

    @Nested
    @DisplayName("Trading Days to Expiry")
    class TradingDaysToExpiry {

        @Test
        @DisplayName("Trading days from Monday to Thursday (same week, no holidays)")
        void tradingDaysSameWeek() {
            LocalDate monday = LocalDate.of(2025, 6, 9);
            LocalDate thursday = LocalDate.of(2025, 6, 12);
            int days = expiryCalendarService.getTradingDaysToExpiry(monday, thursday);
            // Tue, Wed, Thu = 3 trading days
            assertThat(days).isEqualTo(3);
        }

        @Test
        @DisplayName("Trading days across weekend")
        void tradingDaysAcrossWeekend() {
            LocalDate friday = LocalDate.of(2025, 6, 13);
            LocalDate nextThursday = LocalDate.of(2025, 6, 19);
            int days = expiryCalendarService.getTradingDaysToExpiry(friday, nextThursday);
            // Sat(skip), Sun(skip), Mon, Tue, Wed, Thu = 4 trading days
            assertThat(days).isEqualTo(4);
        }

        @Test
        @DisplayName("Trading days with holiday in between")
        void tradingDaysWithHoliday() {
            addHoliday(LocalDate.of(2025, 6, 11), "Test Holiday", HolidayType.FULL_HOLIDAY);

            LocalDate monday = LocalDate.of(2025, 6, 9);
            LocalDate thursday = LocalDate.of(2025, 6, 12);
            int days = expiryCalendarService.getTradingDaysToExpiry(monday, thursday);
            // Tue=trading, Wed=holiday(skip), Thu=trading -> 2 trading days
            assertThat(days).isEqualTo(2);
        }

        @Test
        @DisplayName("Same day returns 0 trading days")
        void sameDayZeroDays() {
            LocalDate today = LocalDate.of(2025, 6, 12);
            int days = expiryCalendarService.getTradingDaysToExpiry(today, today);
            assertThat(days).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Is Expiry Day")
    class IsExpiryDay {

        @Test
        @DisplayName("Thursday is expiry day")
        void thursdayIsExpiryDay() {
            // June 12, 2025 is a Thursday
            assertThat(expiryCalendarService.isExpiryDay(LocalDate.of(2025, 6, 12)))
                    .isTrue();
        }

        @Test
        @DisplayName("Monday is not expiry day")
        void mondayIsNotExpiryDay() {
            assertThat(expiryCalendarService.isExpiryDay(LocalDate.of(2025, 6, 9)))
                    .isFalse();
        }

        @Test
        @DisplayName("Holiday-adjusted expiry day: Wednesday before holiday Thursday")
        void adjustedExpiryDay() {
            addHoliday(LocalDate.of(2025, 6, 12), "Test Holiday", HolidayType.FULL_HOLIDAY);

            // Thursday June 12 is a holiday, so expiry moves to Wednesday June 11
            assertThat(expiryCalendarService.isExpiryDay(LocalDate.of(2025, 6, 11)))
                    .isTrue();
            assertThat(expiryCalendarService.isExpiryDay(LocalDate.of(2025, 6, 12)))
                    .isFalse();
        }
    }

    private void addHoliday(LocalDate date, String name, HolidayType type) {
        HolidayCalendarConfig.Holiday holiday = new HolidayCalendarConfig.Holiday();
        holiday.setDate(date);
        holiday.setName(name);
        holiday.setType(type);
        List<HolidayCalendarConfig.Holiday> holidays = new ArrayList<>(holidayCalendarConfig.getHolidays());
        holidays.add(holiday);
        holidayCalendarConfig.setHolidays(holidays);
    }
}
