package com.algotrader.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the NSE trading calendar, loaded from application.properties
 * or holidays.yml via the {@code trading-calendar} prefix.
 *
 * <p>The holiday list is updated annually based on the NSE published calendar.
 * Holidays are loaded at startup and used by TradingCalendarService for market
 * phase detection and by ExpiryCalendarService for expiry adjustment.
 */
@Component
@ConfigurationProperties(prefix = "trading-calendar")
public class HolidayCalendarConfig {

    private String exchange = "NSE";
    private String timezone = "Asia/Kolkata";
    private List<Holiday> holidays = new ArrayList<>();

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public List<Holiday> getHolidays() {
        return holidays;
    }

    public void setHolidays(List<Holiday> holidays) {
        this.holidays = holidays;
    }

    /**
     * A single holiday entry on the trading calendar.
     */
    public static class Holiday {

        private LocalDate date;
        private String name;
        private HolidayType type;

        public LocalDate getDate() {
            return date;
        }

        public void setDate(LocalDate date) {
            this.date = date;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public HolidayType getType() {
            return type;
        }

        public void setType(HolidayType type) {
            this.type = type;
        }
    }
}
