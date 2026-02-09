package com.algotrader.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.context.ApplicationEvent;

/**
 * Published when a bar completes and indicator values are recalculated.
 *
 * <p>Carries the updated indicator snapshot for a single instrument. Consumed by:
 * <ul>
 *   <li>{@code IndicatorStreamHandler} -- pushes indicator values to frontend via WebSocket</li>
 *   <li>{@code ConditionEngine} -- evaluates condition rules against updated indicator values (#TODO Phase 13)</li>
 * </ul>
 */
public class IndicatorUpdateEvent extends ApplicationEvent {

    private final Long instrumentToken;
    private final String tradingSymbol;
    private final Map<String, BigDecimal> indicators;
    private final LocalDateTime updateTime;

    public IndicatorUpdateEvent(
            Object source, Long instrumentToken, String tradingSymbol, Map<String, BigDecimal> indicators) {
        super(source);
        this.instrumentToken = instrumentToken;
        this.tradingSymbol = tradingSymbol;
        this.indicators = indicators;
        this.updateTime = LocalDateTime.now();
    }

    public Long getInstrumentToken() {
        return instrumentToken;
    }

    public String getTradingSymbol() {
        return tradingSymbol;
    }

    public Map<String, BigDecimal> getIndicators() {
        return indicators;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }
}
