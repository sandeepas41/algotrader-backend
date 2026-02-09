package com.algotrader.calendar;

import com.algotrader.domain.enums.MarketPhase;
import com.algotrader.exception.MarketClosedException;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AOP aspect that enforces the {@link TradingHoursOnly} annotation.
 *
 * <p>Intercepts calls to methods annotated with @TradingHoursOnly and checks
 * the current market phase via TradingCalendarService. If the market is not
 * in an allowed phase, throws {@link MarketClosedException}.
 *
 * <p>This provides a declarative, centralized way to guard trading operations
 * instead of scattering time-check logic across individual services.
 */
@Aspect
@Component
public class TradingHoursGuard {

    private static final Logger log = LoggerFactory.getLogger(TradingHoursGuard.class);

    private final TradingCalendarService tradingCalendarService;

    public TradingHoursGuard(TradingCalendarService tradingCalendarService) {
        this.tradingCalendarService = tradingCalendarService;
    }

    @Around("@annotation(TradingHoursOnly)")
    public Object guardTradingHours(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        TradingHoursOnly annotation = method.getAnnotation(TradingHoursOnly.class);

        boolean allowed;
        if (annotation.allowPreOpen()) {
            allowed = tradingCalendarService.isTradingAllowed();
        } else {
            allowed = tradingCalendarService.isMarketOpen();
        }

        if (!allowed) {
            MarketPhase currentPhase = tradingCalendarService.getCurrentPhase();
            String message = String.format("%s (current phase: %s)", annotation.message(), currentPhase);

            log.warn("Method {} blocked by @TradingHoursOnly: {}", method.getName(), message);

            throw new MarketClosedException(message);
        }

        return joinPoint.proceed();
    }
}
