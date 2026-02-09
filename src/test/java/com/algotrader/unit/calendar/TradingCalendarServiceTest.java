package com.algotrader.unit.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.algotrader.calendar.HolidayCalendarConfig;
import com.algotrader.calendar.HolidayType;
import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.domain.enums.MarketPhase;
import com.algotrader.event.MarketStatusEvent;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for TradingCalendarService covering market phase detection
 * at all boundary times, holiday handling, and trading day queries.
 */
class TradingCalendarServiceTest {

    private HolidayCalendarConfig holidayCalendarConfig;
    private ApplicationEventPublisher applicationEventPublisher;
    private TradingCalendarService tradingCalendarService;

    @BeforeEach
    void setUp() {
        holidayCalendarConfig = new HolidayCalendarConfig();
        holidayCalendarConfig.setExchange("NSE");
        holidayCalendarConfig.setTimezone("Asia/Kolkata");
        holidayCalendarConfig.setHolidays(new ArrayList<>());

        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        tradingCalendarService = new TradingCalendarService(holidayCalendarConfig, applicationEventPublisher);
    }

    @Nested
    @DisplayName("Phase Detection at Boundary Times")
    class PhaseDetection {

        // A regular Wednesday — not a holiday, not a weekend
        private final LocalDate wednesday = LocalDate.of(2025, 6, 11);

        @Test
        @DisplayName("Before 09:00 should be CLOSED")
        void beforePreOpen() {
            assertPhase(wednesday, LocalTime.of(8, 59, 59), MarketPhase.CLOSED);
        }

        @Test
        @DisplayName("At 09:00 should be PRE_OPEN")
        void atPreOpen() {
            assertPhase(wednesday, LocalTime.of(9, 0), MarketPhase.PRE_OPEN);
        }

        @Test
        @DisplayName("At 09:07 should be PRE_OPEN")
        void duringPreOpen() {
            assertPhase(wednesday, LocalTime.of(9, 7), MarketPhase.PRE_OPEN);
        }

        @Test
        @DisplayName("At 09:08 should be PRE_OPEN_ORDER_MATCHING")
        void atPreOpenOrderMatching() {
            assertPhase(wednesday, LocalTime.of(9, 8), MarketPhase.PRE_OPEN_ORDER_MATCHING);
        }

        @Test
        @DisplayName("At 09:14 should be PRE_OPEN_ORDER_MATCHING")
        void duringPreOpenOrderMatching() {
            assertPhase(wednesday, LocalTime.of(9, 14), MarketPhase.PRE_OPEN_ORDER_MATCHING);
        }

        @Test
        @DisplayName("At 09:15 should be NORMAL")
        void atNormalOpen() {
            assertPhase(wednesday, LocalTime.of(9, 15), MarketPhase.NORMAL);
        }

        @Test
        @DisplayName("At 12:00 should be NORMAL")
        void duringNormal() {
            assertPhase(wednesday, LocalTime.of(12, 0), MarketPhase.NORMAL);
        }

        @Test
        @DisplayName("At 15:29 should be NORMAL")
        void justBeforeClose() {
            assertPhase(wednesday, LocalTime.of(15, 29), MarketPhase.NORMAL);
        }

        @Test
        @DisplayName("At 15:30 should be CLOSING")
        void atClosing() {
            assertPhase(wednesday, LocalTime.of(15, 30), MarketPhase.CLOSING);
        }

        @Test
        @DisplayName("At 15:39 should be CLOSING")
        void duringClosing() {
            assertPhase(wednesday, LocalTime.of(15, 39), MarketPhase.CLOSING);
        }

        @Test
        @DisplayName("At 15:40 should be POST_CLOSE")
        void atPostClose() {
            assertPhase(wednesday, LocalTime.of(15, 40), MarketPhase.POST_CLOSE);
        }

        @Test
        @DisplayName("At 15:59 should be POST_CLOSE")
        void duringPostClose() {
            assertPhase(wednesday, LocalTime.of(15, 59), MarketPhase.POST_CLOSE);
        }

        @Test
        @DisplayName("At 16:00 should be CLOSED")
        void atClosed() {
            assertPhase(wednesday, LocalTime.of(16, 0), MarketPhase.CLOSED);
        }

        @Test
        @DisplayName("At 23:00 should be CLOSED")
        void lateNight() {
            assertPhase(wednesday, LocalTime.of(23, 0), MarketPhase.CLOSED);
        }

        private void assertPhase(LocalDate date, LocalTime time, MarketPhase expected) {
            MarketPhase phase = tradingCalendarService.calculatePhase(date, time);
            assertThat(phase).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("Weekend Detection")
    class WeekendDetection {

        @Test
        @DisplayName("Saturday should be CLOSED at any time")
        void saturdayIsClosed() {
            LocalDate saturday = LocalDate.of(2025, 6, 14);
            assertThat(tradingCalendarService.calculatePhase(saturday, LocalTime.of(10, 0)))
                    .isEqualTo(MarketPhase.CLOSED);
        }

        @Test
        @DisplayName("Sunday should be CLOSED at any time")
        void sundayIsClosed() {
            LocalDate sunday = LocalDate.of(2025, 6, 15);
            assertThat(tradingCalendarService.calculatePhase(sunday, LocalTime.of(10, 0)))
                    .isEqualTo(MarketPhase.CLOSED);
        }
    }

    @Nested
    @DisplayName("Holiday Detection")
    class HolidayDetection {

        @Test
        @DisplayName("Weekend should be a holiday")
        void weekendIsHoliday() {
            LocalDate saturday = LocalDate.of(2025, 6, 14);
            assertThat(tradingCalendarService.isHoliday(saturday)).isTrue();
        }

        @Test
        @DisplayName("Regular weekday should not be a holiday")
        void weekdayIsNotHoliday() {
            LocalDate wednesday = LocalDate.of(2025, 6, 11);
            assertThat(tradingCalendarService.isHoliday(wednesday)).isFalse();
        }

        @Test
        @DisplayName("Configured FULL_HOLIDAY should be detected as holiday")
        void fullHolidayDetected() {
            addHoliday(LocalDate.of(2025, 8, 15), "Independence Day", HolidayType.FULL_HOLIDAY);

            assertThat(tradingCalendarService.isHoliday(LocalDate.of(2025, 8, 15)))
                    .isTrue();
        }

        @Test
        @DisplayName("MUHURAT_TRADING day should NOT be detected as holiday")
        void muhuratTradingNotHoliday() {
            addHoliday(LocalDate.of(2025, 10, 21), "Diwali (Muhurat Trading)", HolidayType.MUHURAT_TRADING);

            // isHoliday only checks FULL_HOLIDAY, not MUHURAT_TRADING
            assertThat(tradingCalendarService.isHoliday(LocalDate.of(2025, 10, 21)))
                    .isFalse();
        }

        @Test
        @DisplayName("FULL_HOLIDAY on weekday should return CLOSED for calculatePhase")
        void holidayPhaseIsClosed() {
            addHoliday(LocalDate.of(2025, 8, 15), "Independence Day", HolidayType.FULL_HOLIDAY);

            MarketPhase phase = tradingCalendarService.calculatePhase(LocalDate.of(2025, 8, 15), LocalTime.of(10, 0));
            assertThat(phase).isEqualTo(MarketPhase.CLOSED);
        }
    }

    @Nested
    @DisplayName("Muhurat Trading Detection")
    class MuhuratTradingDetection {

        @Test
        @DisplayName("MUHURAT_TRADING date should be detected")
        void muhuratTradingDetected() {
            addHoliday(LocalDate.of(2025, 10, 21), "Diwali (Muhurat Trading)", HolidayType.MUHURAT_TRADING);

            assertThat(tradingCalendarService.isMuhuratTrading(LocalDate.of(2025, 10, 21)))
                    .isTrue();
        }

        @Test
        @DisplayName("Non-Muhurat date should not be detected as Muhurat trading")
        void nonMuhuratNotDetected() {
            assertThat(tradingCalendarService.isMuhuratTrading(LocalDate.of(2025, 6, 11)))
                    .isFalse();
        }

        @Test
        @DisplayName("MUHURAT_TRADING day is a trading day")
        void muhuratIsTradingDay() {
            addHoliday(LocalDate.of(2025, 10, 21), "Diwali (Muhurat Trading)", HolidayType.MUHURAT_TRADING);

            assertThat(tradingCalendarService.isTradingDay(LocalDate.of(2025, 10, 21)))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Trading Day Queries")
    class TradingDayQueries {

        @Test
        @DisplayName("Regular weekday is a trading day")
        void weekdayIsTradingDay() {
            assertThat(tradingCalendarService.isTradingDay(LocalDate.of(2025, 6, 11)))
                    .isTrue();
        }

        @Test
        @DisplayName("Saturday is not a trading day")
        void saturdayIsNotTradingDay() {
            assertThat(tradingCalendarService.isTradingDay(LocalDate.of(2025, 6, 14)))
                    .isFalse();
        }

        @Test
        @DisplayName("FULL_HOLIDAY is not a trading day")
        void fullHolidayNotTradingDay() {
            addHoliday(LocalDate.of(2025, 8, 15), "Independence Day", HolidayType.FULL_HOLIDAY);

            assertThat(tradingCalendarService.isTradingDay(LocalDate.of(2025, 8, 15)))
                    .isFalse();
        }

        @Test
        @DisplayName("getNextTradingDay skips weekends")
        void nextTradingDaySkipsWeekend() {
            // Friday June 13, 2025 -> next trading day should be Monday June 16
            LocalDate friday = LocalDate.of(2025, 6, 13);
            LocalDate nextTrading = tradingCalendarService.getNextTradingDay(friday);
            assertThat(nextTrading).isEqualTo(LocalDate.of(2025, 6, 16));
        }

        @Test
        @DisplayName("getNextTradingDay skips holidays")
        void nextTradingDaySkipsHolidays() {
            // Thursday Aug 14, next day Aug 15 is Independence Day (Friday)
            // Then Sat+Sun -> next trading day = Monday Aug 18
            addHoliday(LocalDate.of(2025, 8, 15), "Independence Day", HolidayType.FULL_HOLIDAY);

            LocalDate aug14 = LocalDate.of(2025, 8, 14);
            LocalDate nextTrading = tradingCalendarService.getNextTradingDay(aug14);
            assertThat(nextTrading).isEqualTo(LocalDate.of(2025, 8, 18));
        }

        @Test
        @DisplayName("getPreviousTradingDay skips weekends")
        void previousTradingDaySkipsWeekend() {
            // Monday June 16 -> previous trading day should be Friday June 13
            LocalDate monday = LocalDate.of(2025, 6, 16);
            LocalDate prevTrading = tradingCalendarService.getPreviousTradingDay(monday);
            assertThat(prevTrading).isEqualTo(LocalDate.of(2025, 6, 13));
        }

        @Test
        @DisplayName("getPreviousTradingDay skips holidays")
        void previousTradingDaySkipsHolidays() {
            // Monday Aug 18 -> Aug 15 is holiday, previous is Thursday Aug 14
            addHoliday(LocalDate.of(2025, 8, 15), "Independence Day", HolidayType.FULL_HOLIDAY);

            LocalDate aug18 = LocalDate.of(2025, 8, 18);
            LocalDate prevTrading = tradingCalendarService.getPreviousTradingDay(aug18);
            assertThat(prevTrading).isEqualTo(LocalDate.of(2025, 8, 14));
        }
    }

    @Nested
    @DisplayName("Market Phase Transitions")
    class MarketPhaseTransitions {

        @Test
        @DisplayName("Phase transition publishes MarketStatusEvent")
        void phaseTransitionPublishesEvent() {
            LocalDate wednesday = LocalDate.of(2025, 6, 11);

            // Start from CLOSED, move to PRE_OPEN
            tradingCalendarService.updateMarketPhase(wednesday, LocalTime.of(9, 0));

            ArgumentCaptor<MarketStatusEvent> captor = ArgumentCaptor.forClass(MarketStatusEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());

            MarketStatusEvent event = captor.getValue();
            assertThat(event.getPreviousPhase()).isEqualTo(MarketPhase.CLOSED);
            assertThat(event.getCurrentPhase()).isEqualTo(MarketPhase.PRE_OPEN);
        }

        @Test
        @DisplayName("Same phase does not publish event")
        void samePhaseDoesNotPublish() {
            LocalDate wednesday = LocalDate.of(2025, 6, 11);

            // Force phase to NORMAL first
            tradingCalendarService.updateMarketPhase(wednesday, LocalTime.of(10, 0));
            reset(applicationEventPublisher);

            // Call again at same phase
            tradingCalendarService.updateMarketPhase(wednesday, LocalTime.of(11, 0));
            verify(applicationEventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("Holiday forces CLOSED phase and publishes transition")
        void holidayForcesClosed() {
            addHoliday(LocalDate.of(2025, 8, 15), "Independence Day", HolidayType.FULL_HOLIDAY);

            // Simulate previous phase was PRE_OPEN (shouldn't happen, but tests the guard)
            LocalDate wednesday = LocalDate.of(2025, 6, 11);
            tradingCalendarService.updateMarketPhase(wednesday, LocalTime.of(9, 0));
            reset(applicationEventPublisher);

            // Now call on holiday
            tradingCalendarService.updateMarketPhase(LocalDate.of(2025, 8, 15), LocalTime.of(10, 0));

            ArgumentCaptor<MarketStatusEvent> captor = ArgumentCaptor.forClass(MarketStatusEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getCurrentPhase()).isEqualTo(MarketPhase.CLOSED);
        }
    }

    @Nested
    @DisplayName("isMarketOpen and isTradingAllowed")
    class MarketStatusQueries {

        @Test
        @DisplayName("isMarketOpen returns true only during NORMAL phase")
        void isMarketOpenDuringNormal() {
            LocalDate wednesday = LocalDate.of(2025, 6, 11);
            tradingCalendarService.updateMarketPhase(wednesday, LocalTime.of(10, 0));
            assertThat(tradingCalendarService.isMarketOpen()).isTrue();
        }

        @Test
        @DisplayName("isMarketOpen returns false during PRE_OPEN")
        void isMarketOpenFalseDuringPreOpen() {
            LocalDate wednesday = LocalDate.of(2025, 6, 11);
            tradingCalendarService.updateMarketPhase(wednesday, LocalTime.of(9, 0));
            assertThat(tradingCalendarService.isMarketOpen()).isFalse();
        }

        @Test
        @DisplayName("isTradingAllowed returns true during PRE_OPEN")
        void isTradingAllowedDuringPreOpen() {
            LocalDate wednesday = LocalDate.of(2025, 6, 11);
            tradingCalendarService.updateMarketPhase(wednesday, LocalTime.of(9, 0));
            assertThat(tradingCalendarService.isTradingAllowed()).isTrue();
        }

        @Test
        @DisplayName("isTradingAllowed returns true during NORMAL")
        void isTradingAllowedDuringNormal() {
            LocalDate wednesday = LocalDate.of(2025, 6, 11);
            tradingCalendarService.updateMarketPhase(wednesday, LocalTime.of(10, 0));
            assertThat(tradingCalendarService.isTradingAllowed()).isTrue();
        }

        @Test
        @DisplayName("isTradingAllowed returns false during CLOSING")
        void isTradingAllowedFalseDuringClosing() {
            LocalDate wednesday = LocalDate.of(2025, 6, 11);
            tradingCalendarService.updateMarketPhase(wednesday, LocalTime.of(15, 35));
            assertThat(tradingCalendarService.isTradingAllowed()).isFalse();
        }

        @Test
        @DisplayName("isMarketHours returns true during any non-CLOSED phase")
        void isMarketHoursDuringPostClose() {
            LocalDate wednesday = LocalDate.of(2025, 6, 11);
            tradingCalendarService.updateMarketPhase(wednesday, LocalTime.of(15, 45));
            assertThat(tradingCalendarService.isMarketHours()).isTrue();
        }

        @Test
        @DisplayName("isMarketHours returns false when CLOSED")
        void isMarketHoursFalseWhenClosed() {
            // Default phase is CLOSED
            assertThat(tradingCalendarService.isMarketHours()).isFalse();
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
