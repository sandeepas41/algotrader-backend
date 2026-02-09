package com.algotrader.calendar;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Guards a method so it can only be invoked during market hours.
 *
 * <p>By default, the method is only allowed during the NORMAL trading session
 * (09:15-15:30 IST). If {@link #allowPreOpen()} is set to true, the method
 * is also allowed during the PRE_OPEN phase (09:00-09:08).
 *
 * <p>When invoked outside allowed hours, a {@link com.algotrader.exception.MarketClosedException}
 * is thrown with the configured {@link #message()}.
 *
 * <p>Enforced by {@link TradingHoursGuard} AOP aspect.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TradingHoursOnly {

    /** If true, allows execution during PRE_OPEN phase as well as NORMAL. */
    boolean allowPreOpen() default false;

    /** Message to include in the exception if called outside market hours. */
    String message() default "This operation is only available during market hours";
}
