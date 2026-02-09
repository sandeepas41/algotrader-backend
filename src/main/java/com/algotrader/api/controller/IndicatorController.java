package com.algotrader.api.controller;

import com.algotrader.api.dto.response.BarDataResponse;
import com.algotrader.api.dto.response.TrackedInstrumentResponse;
import com.algotrader.indicator.BarSeriesManager;
import com.algotrader.indicator.IndicatorMetadata;
import com.algotrader.indicator.IndicatorService;
import com.algotrader.indicator.IndicatorType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for querying technical indicator values, bar data, and tracked instruments.
 *
 * <p>Provides real-time indicator snapshots computed by the IndicatorService.
 * Indicator values are recalculated on each bar completion, so REST reads return
 * the latest cached value (O(1) read from ConcurrentHashMap).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/indicators/{token}} -- all indicator values for an instrument</li>
 *   <li>{@code GET /api/indicators/{token}/{type}} -- specific indicator value</li>
 *   <li>{@code GET /api/indicators/{token}/bars} -- OHLCV bar data for charting</li>
 *   <li>{@code GET /api/indicators/tracked} -- list of tracked instruments</li>
 *   <li>{@code GET /api/indicators/metadata} -- indicator type metadata for UI</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/indicators")
public class IndicatorController {

    private final IndicatorService indicatorService;

    public IndicatorController(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;
    }

    /**
     * Returns all current indicator values for an instrument.
     */
    @GetMapping("/{instrumentToken}")
    public ResponseEntity<Map<String, BigDecimal>> getIndicators(@PathVariable Long instrumentToken) {
        Map<String, BigDecimal> snapshot = indicatorService.getIndicatorSnapshot(instrumentToken);
        if (snapshot.isEmpty() && !indicatorService.isTracked(instrumentToken)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(snapshot);
    }

    /**
     * Returns a specific indicator value for an instrument.
     */
    @GetMapping("/{instrumentToken}/{indicatorType}")
    public ResponseEntity<BigDecimal> getIndicator(
            @PathVariable Long instrumentToken,
            @PathVariable IndicatorType indicatorType,
            @RequestParam(required = false) Integer period,
            @RequestParam(required = false) String field) {

        BigDecimal value = indicatorService.getIndicatorValue(instrumentToken, indicatorType, period, field);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(value);
    }

    /**
     * Returns OHLCV bar data for an instrument (for charting).
     */
    @GetMapping("/{instrumentToken}/bars")
    public ResponseEntity<List<BarDataResponse>> getBars(
            @PathVariable Long instrumentToken, @RequestParam(defaultValue = "100") int count) {

        List<BarSeriesManager.BarSnapshot> bars = indicatorService.getBarData(instrumentToken, count);
        if (bars.isEmpty() && !indicatorService.isTracked(instrumentToken)) {
            return ResponseEntity.notFound().build();
        }

        List<BarDataResponse> response = bars.stream()
                .map(bar -> BarDataResponse.builder()
                        .timestamp(bar.timestamp())
                        .open(bar.open())
                        .high(bar.high())
                        .low(bar.low())
                        .close(bar.close())
                        .volume(bar.volume())
                        .build())
                .toList();

        return ResponseEntity.ok(response);
    }

    /**
     * Returns all tracked instruments with their configuration and current state.
     */
    @GetMapping("/tracked")
    public List<TrackedInstrumentResponse> getTrackedInstruments() {
        return indicatorService.getTrackedInstruments().stream()
                .map(ti -> TrackedInstrumentResponse.builder()
                        .instrumentToken(ti.instrumentToken())
                        .tradingSymbol(ti.tradingSymbol())
                        .barDurationSeconds(ti.barDurationSeconds())
                        .barCount(ti.barCount())
                        .indicatorCount(ti.indicatorCount())
                        .build())
                .toList();
    }

    /**
     * Returns metadata for all available indicator types (for condition builder UI).
     */
    @GetMapping("/metadata")
    public List<IndicatorMetadata> getAvailableIndicators() {
        return indicatorService.getAvailableIndicators();
    }
}
