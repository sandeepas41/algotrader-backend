package com.algotrader.unit.indicator;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.IndicatorController;
import com.algotrader.config.ApiResponseAdvice;
import com.algotrader.indicator.BarSeriesManager;
import com.algotrader.indicator.IndicatorMetadata;
import com.algotrader.indicator.IndicatorService;
import com.algotrader.indicator.IndicatorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc tests for the IndicatorController REST endpoints.
 * Uses ApiResponseAdvice to match production wrapping behavior.
 */
@ExtendWith(MockitoExtension.class)
class IndicatorControllerTest {

    private MockMvc mockMvc;

    @Mock
    private IndicatorService indicatorService;

    @BeforeEach
    void setUp() {
        IndicatorController controller = new IndicatorController(indicatorService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiResponseAdvice())
                .build();
    }

    @Nested
    @DisplayName("GET /api/indicators/{instrumentToken}")
    class GetIndicators {

        @Test
        @DisplayName("returns indicator snapshot for tracked instrument")
        void returnsSnapshotForTrackedInstrument() throws Exception {
            Map<String, BigDecimal> snapshot = new LinkedHashMap<>();
            snapshot.put("RSI:14", BigDecimal.valueOf(65.50));
            snapshot.put("EMA:21", BigDecimal.valueOf(22550.75));
            when(indicatorService.getIndicatorSnapshot(256265L)).thenReturn(snapshot);

            mockMvc.perform(get("/api/indicators/256265"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data['RSI:14']").value(65.50))
                    .andExpect(jsonPath("$.data['EMA:21']").value(22550.75));
        }

        @Test
        @DisplayName("returns 404 for non-tracked instrument with empty snapshot")
        void returns404ForNonTracked() throws Exception {
            when(indicatorService.getIndicatorSnapshot(999999L)).thenReturn(Collections.emptyMap());
            when(indicatorService.isTracked(999999L)).thenReturn(false);

            mockMvc.perform(get("/api/indicators/999999")).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("returns empty map for tracked instrument with no data yet")
        void returnsEmptyMapForTrackedNoData() throws Exception {
            when(indicatorService.getIndicatorSnapshot(256265L)).thenReturn(Collections.emptyMap());
            when(indicatorService.isTracked(256265L)).thenReturn(true);

            mockMvc.perform(get("/api/indicators/256265")).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("GET /api/indicators/{instrumentToken}/{indicatorType}")
    class GetSpecificIndicator {

        @Test
        @DisplayName("returns value for specific indicator")
        void returnsValueForSpecificIndicator() throws Exception {
            when(indicatorService.getIndicatorValue(eq(256265L), eq(IndicatorType.RSI), eq(14), eq(null)))
                    .thenReturn(BigDecimal.valueOf(72.35));

            mockMvc.perform(get("/api/indicators/256265/RSI").param("period", "14"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(72.35));
        }

        @Test
        @DisplayName("returns 404 when indicator value not found")
        void returns404WhenValueNotFound() throws Exception {
            when(indicatorService.getIndicatorValue(eq(256265L), eq(IndicatorType.RSI), eq(null), eq(null)))
                    .thenReturn(null);

            mockMvc.perform(get("/api/indicators/256265/RSI")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/indicators/{instrumentToken}/bars")
    class GetBars {

        @Test
        @DisplayName("returns bar data for instrument")
        void returnsBarData() throws Exception {
            LocalDateTime timestamp = LocalDateTime.of(2025, 2, 10, 9, 16, 0);
            List<BarSeriesManager.BarSnapshot> bars = List.of(new BarSeriesManager.BarSnapshot(
                    timestamp,
                    BigDecimal.valueOf(22400),
                    BigDecimal.valueOf(22600),
                    BigDecimal.valueOf(22350),
                    BigDecimal.valueOf(22550),
                    50000));

            when(indicatorService.getBarData(eq(256265L), anyInt())).thenReturn(bars);

            mockMvc.perform(get("/api/indicators/256265/bars"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].open").value(22400))
                    .andExpect(jsonPath("$.data[0].high").value(22600))
                    .andExpect(jsonPath("$.data[0].low").value(22350))
                    .andExpect(jsonPath("$.data[0].close").value(22550))
                    .andExpect(jsonPath("$.data[0].volume").value(50000));
        }

        @Test
        @DisplayName("returns 404 for non-tracked instrument")
        void returns404ForNonTracked() throws Exception {
            when(indicatorService.getBarData(eq(999999L), anyInt())).thenReturn(Collections.emptyList());
            when(indicatorService.isTracked(999999L)).thenReturn(false);

            mockMvc.perform(get("/api/indicators/999999/bars")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/indicators/tracked")
    class GetTracked {

        @Test
        @DisplayName("returns list of tracked instruments")
        void returnsTrackedInstruments() throws Exception {
            List<IndicatorService.TrackedInstrument> tracked =
                    List.of(new IndicatorService.TrackedInstrument(256265L, "NIFTY", 60, 100, 5));

            when(indicatorService.getTrackedInstruments()).thenReturn(tracked);

            mockMvc.perform(get("/api/indicators/tracked"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].instrumentToken").value(256265))
                    .andExpect(jsonPath("$.data[0].tradingSymbol").value("NIFTY"))
                    .andExpect(jsonPath("$.data[0].barDurationSeconds").value(60))
                    .andExpect(jsonPath("$.data[0].barCount").value(100))
                    .andExpect(jsonPath("$.data[0].indicatorCount").value(5));
        }
    }

    @Nested
    @DisplayName("GET /api/indicators/metadata")
    class GetMetadata {

        @Test
        @DisplayName("returns metadata list")
        void returnsMetadataList() throws Exception {
            when(indicatorService.getAvailableIndicators()).thenReturn(IndicatorMetadata.allMetadata());

            mockMvc.perform(get("/api/indicators/metadata"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(IndicatorType.values().length));
        }
    }
}
