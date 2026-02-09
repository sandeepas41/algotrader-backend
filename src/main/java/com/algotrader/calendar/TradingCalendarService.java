package com.algotrader.calendar;

import com.algotrader.domain.enums.MarketPhase;
import com.algotrader.event.MarketStatusEvent;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Central service for market hours awareness, holiday detection, and phase transitions.
 *
 * <p>Polls every 5 seconds to detect market phase changes and publishes
 * {@link MarketStatusEvent} on transitions. Other components listen to these events
 * to adapt behavior (e.g., strategies only trade during NORMAL phase, risk counters
 * reset on CLOSED->PRE_OPEN, instruments refresh on market open).
 *
 * <p>Holiday data is loaded from YAML configuration via {@link HolidayCalendarConfig}.
 * The calendar is updated annually based on the NSE published holiday schedule.
 */
@Service
public class TradingCalendarService {

    private static final Logger log = LoggerFactory.getLogger(TradingCalendarService.class);

    private final HolidayCalendarConfig holidayCalendarConfig;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final AtomicReference<MarketPhase> currentPhase = new AtomicReference<>(MarketPhase.CLOSED);

    public TradingCalendarService(
            HolidayCalendarConfig holidayCalendarConfig, ApplicationEventPublisher applicationEventPublisher) {
        this.holidayCalendarConfig = holidayCalendarConfig;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Checks for market phase transitions every 5 seconds.
     * Publishes a MarketStatusEvent when the phase changes.
     */
    @Scheduled(fixedRate = 5000)
    public void updateMarketPhase() {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        updateMarketPhase(today, now);
    }

    /**
     * Testable version: determines the market phase for a given date and time.
     */
    public void updateMarketPhase(LocalDate date, LocalTime time) {
        if (isHoliday(date)) {
            if (currentPhase.get() != MarketPhase.CLOSED) {
                MarketPhase previous = currentPhase.getAndSet(MarketPhase.CLOSED);
                publishPhaseTransition(previous, MarketPhase.CLOSED);
            }
            return;
        }

        MarketPhase newPhase = calculatePhase(date, time);
        MarketPhase previousPhase = currentPhase.getAndSet(newPhase);

        if (previousPhase != newPhase) {
            publishPhaseTransition(previousPhase, newPhase);
            log.info("Market phase transition: {} -> {}", previousPhase, newPhase);
        }
    }

    public MarketPhase getCurrentPhase() {
        return currentPhase.get();
    }

    /** Returns true only during the NORMAL trading session (9:15-15:30). */
    public boolean isMarketOpen() {
        return currentPhase.get() == MarketPhase.NORMAL;
    }

    /** Returns true during PRE_OPEN or NORMAL — phases where orders can be placed. */
    public boolean isTradingAllowed() {
        MarketPhase phase = currentPhase.get();
        return phase == MarketPhase.NORMAL || phase == MarketPhase.PRE_OPEN;
    }

    /** Returns true during any market phase except CLOSED. */
    public boolean isMarketHours() {
        return currentPhase.get() != MarketPhase.CLOSED;
    }

    /**
     * Checks if a date is a non-trading day (weekend or full holiday).
     * Weekends (Saturday/Sunday) are always holidays.
     */
    public boolean isHoliday(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return true;
        }
        return holidayCalendarConfig.getHolidays().stream()
                .anyMatch(h -> h.getDate().equals(date) && h.getType() == HolidayType.FULL_HOLIDAY);
    }

    /** Returns true if the date has a Muhurat trading session (Diwali). */
    public boolean isMuhuratTrading(LocalDate date) {
        return holidayCalendarConfig.getHolidays().stream()
                .anyMatch(h -> h.getDate().equals(date) && h.getType() == HolidayType.MUHURAT_TRADING);
    }

    /**
     * Returns true if the date is a trading day.
     * A day is a trading day if it's not a holiday, OR if it has Muhurat trading.
     */
    public boolean isTradingDay(LocalDate date) {
        return !isHoliday(date) || isMuhuratTrading(date);
    }

    /** Returns the next trading day after the given date. */
    public LocalDate getNextTradingDay(LocalDate from) {
        LocalDate next = from.plusDays(1);
        while (!isTradingDay(next)) {
            next = next.plusDays(1);
        }
        return next;
    }

    /** Returns the previous trading day before the given date. */
    public LocalDate getPreviousTradingDay(LocalDate from) {
        LocalDate prev = from.minusDays(1);
        while (!isTradingDay(prev)) {
            prev = prev.minusDays(1);
        }
        return prev;
    }

    /** Returns minutes until the NORMAL session ends (15:30). Zero if already past. */
    public long getMinutesToClose() {
        LocalTime now = LocalTime.now();
        LocalTime closeTime = MarketPhase.NORMAL.getEndTime();
        if (now.isBefore(closeTime)) {
            return Duration.between(now, closeTime).toMinutes();
        }
        return 0;
    }

    /**
     * Determines the market phase for a given date and time.
     */
    public MarketPhase calculatePhase(LocalDate date, LocalTime time) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return MarketPhase.CLOSED;
        }
        if (isHoliday(date)) {
            return MarketPhase.CLOSED;
        }

        if (time.isBefore(MarketPhase.PRE_OPEN.getStartTime())) {
            return MarketPhase.CLOSED;
        }
        if (time.isBefore(MarketPhase.PRE_OPEN_ORDER_MATCHING.getStartTime())) {
            return MarketPhase.PRE_OPEN;
        }
        if (time.isBefore(MarketPhase.NORMAL.getStartTime())) {
            return MarketPhase.PRE_OPEN_ORDER_MATCHING;
        }
        if (time.isBefore(MarketPhase.CLOSING.getStartTime())) {
            return MarketPhase.NORMAL;
        }
        if (time.isBefore(MarketPhase.POST_CLOSE.getStartTime())) {
            return MarketPhase.CLOSING;
        }
        if (time.isBefore(MarketPhase.POST_CLOSE.getEndTime())) {
            return MarketPhase.POST_CLOSE;
        }

        return MarketPhase.CLOSED;
    }

    private void publishPhaseTransition(MarketPhase previous, MarketPhase current) {
        applicationEventPublisher.publishEvent(new MarketStatusEvent(this, previous, current));
    }
}
