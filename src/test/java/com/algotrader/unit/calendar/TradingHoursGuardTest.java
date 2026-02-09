package com.algotrader.unit.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.algotrader.calendar.TradingCalendarService;
import com.algotrader.calendar.TradingHoursGuard;
import com.algotrader.calendar.TradingHoursOnly;
import com.algotrader.domain.enums.MarketPhase;
import com.algotrader.exception.MarketClosedException;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TradingHoursGuard AOP aspect verifying that
 * method execution is blocked or allowed based on market phase.
 */
class TradingHoursGuardTest {

    private TradingCalendarService tradingCalendarService;
    private TradingHoursGuard tradingHoursGuard;

    @BeforeEach
    void setUp() {
        tradingCalendarService = mock(TradingCalendarService.class);
        tradingHoursGuard = new TradingHoursGuard(tradingCalendarService);
    }

    @Nested
    @DisplayName("Default @TradingHoursOnly (allowPreOpen=false)")
    class DefaultTradingHoursOnly {

        @Test
        @DisplayName("Allows execution when market is open (NORMAL phase)")
        void allowsWhenMarketOpen() throws Throwable {
            when(tradingCalendarService.isMarketOpen()).thenReturn(true);
            when(tradingCalendarService.getCurrentPhase()).thenReturn(MarketPhase.NORMAL);

            ProceedingJoinPoint joinPoint = mockJoinPoint("defaultGuardedMethod");
            when(joinPoint.proceed()).thenReturn("success");

            Object result = tradingHoursGuard.guardTradingHours(joinPoint);
            assertThat(result).isEqualTo("success");
        }

        @Test
        @DisplayName("Blocks execution when market is closed")
        void blocksWhenMarketClosed() throws Throwable {
            when(tradingCalendarService.isMarketOpen()).thenReturn(false);
            when(tradingCalendarService.getCurrentPhase()).thenReturn(MarketPhase.CLOSED);

            ProceedingJoinPoint joinPoint = mockJoinPoint("defaultGuardedMethod");

            assertThatThrownBy(() -> tradingHoursGuard.guardTradingHours(joinPoint))
                    .isInstanceOf(MarketClosedException.class)
                    .hasMessageContaining("CLOSED");
        }

        @Test
        @DisplayName("Blocks execution during PRE_OPEN (allowPreOpen=false)")
        void blocksDuringPreOpen() throws Throwable {
            when(tradingCalendarService.isMarketOpen()).thenReturn(false);
            when(tradingCalendarService.getCurrentPhase()).thenReturn(MarketPhase.PRE_OPEN);

            ProceedingJoinPoint joinPoint = mockJoinPoint("defaultGuardedMethod");

            assertThatThrownBy(() -> tradingHoursGuard.guardTradingHours(joinPoint))
                    .isInstanceOf(MarketClosedException.class)
                    .hasMessageContaining("PRE_OPEN");
        }
    }

    @Nested
    @DisplayName("@TradingHoursOnly(allowPreOpen=true)")
    class AllowPreOpenTradingHoursOnly {

        @Test
        @DisplayName("Allows execution during PRE_OPEN when allowPreOpen=true")
        void allowsDuringPreOpen() throws Throwable {
            when(tradingCalendarService.isTradingAllowed()).thenReturn(true);

            ProceedingJoinPoint joinPoint = mockJoinPoint("preOpenGuardedMethod");
            when(joinPoint.proceed()).thenReturn("pre-open-success");

            Object result = tradingHoursGuard.guardTradingHours(joinPoint);
            assertThat(result).isEqualTo("pre-open-success");
        }

        @Test
        @DisplayName("Blocks execution during CLOSED even with allowPreOpen=true")
        void blocksDuringClosed() throws Throwable {
            when(tradingCalendarService.isTradingAllowed()).thenReturn(false);
            when(tradingCalendarService.getCurrentPhase()).thenReturn(MarketPhase.CLOSED);

            ProceedingJoinPoint joinPoint = mockJoinPoint("preOpenGuardedMethod");

            assertThatThrownBy(() -> tradingHoursGuard.guardTradingHours(joinPoint))
                    .isInstanceOf(MarketClosedException.class)
                    .hasMessageContaining("CLOSED");
        }
    }

    @Nested
    @DisplayName("Custom Message")
    class CustomMessage {

        @Test
        @DisplayName("Custom message is included in exception")
        void customMessageInException() throws Throwable {
            when(tradingCalendarService.isMarketOpen()).thenReturn(false);
            when(tradingCalendarService.getCurrentPhase()).thenReturn(MarketPhase.POST_CLOSE);

            ProceedingJoinPoint joinPoint = mockJoinPoint("customMessageMethod");

            assertThatThrownBy(() -> tradingHoursGuard.guardTradingHours(joinPoint))
                    .isInstanceOf(MarketClosedException.class)
                    .hasMessageContaining("Orders can only be placed during trading hours")
                    .hasMessageContaining("POST_CLOSE");
        }
    }

    // --- Helper methods ---

    private ProceedingJoinPoint mockJoinPoint(String methodName) throws NoSuchMethodException {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = AnnotatedMethods.class.getMethod(methodName);

        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);

        return joinPoint;
    }

    /**
     * Holder class with methods annotated with @TradingHoursOnly for testing.
     */
    public static class AnnotatedMethods {

        @TradingHoursOnly
        public void defaultGuardedMethod() {}

        @TradingHoursOnly(allowPreOpen = true)
        public void preOpenGuardedMethod() {}

        @TradingHoursOnly(message = "Orders can only be placed during trading hours")
        public void customMessageMethod() {}
    }
}
