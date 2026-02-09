package com.algotrader.unit.timeseries;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.OhlcController;
import com.algotrader.config.ApiResponseAdvice;
import com.algotrader.timeseries.Candle;
import com.algotrader.timeseries.CandleInterval;
import com.algotrader.timeseries.OhlcService;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
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
 * Standalone MockMvc tests for OhlcController.
 * Validates REST endpoint behavior, parameter binding, and response structure.
 */
@ExtendWith(MockitoExtension.class)
class OhlcControllerTest {

    private MockMvc mockMvc;

    @Mock
    private OhlcService ohlcService;

    @BeforeEach
    void setUp() {
        OhlcController controller = new OhlcController(ohlcService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiResponseAdvice())
                .build();
    }

    @Nested
    @DisplayName("GET /api/market-data/candles")
    class GetCandles {

        @Test
        @DisplayName("returns candles for valid parameters")
        void returnsCandles() throws Exception {
            long t1 = 1700000000000L;

            Candle candle = Candle.builder()
                    .instrumentToken(256265)
                    .interval(CandleInterval.ONE_MINUTE)
                    .timestamp(t1)
                    .open(BigDecimal.valueOf(22500))
                    .high(BigDecimal.valueOf(22520))
                    .low(BigDecimal.valueOf(22490))
                    .close(BigDecimal.valueOf(22515))
                    .volume(15000)
                    .build();

            when(ohlcService.getCandles(eq(256265L), eq(CandleInterval.ONE_MINUTE), eq(t1), eq(t1 + 300000)))
                    .thenReturn(List.of(candle));

            mockMvc.perform(get("/api/market-data/candles")
                            .param("token", "256265")
                            .param("interval", "1m")
                            .param("from", String.valueOf(t1))
                            .param("to", String.valueOf(t1 + 300000)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].timestamp").value(t1))
                    .andExpect(jsonPath("$.data[0].open").value(22500))
                    .andExpect(jsonPath("$.data[0].high").value(22520))
                    .andExpect(jsonPath("$.data[0].low").value(22490))
                    .andExpect(jsonPath("$.data[0].close").value(22515))
                    .andExpect(jsonPath("$.data[0].volume").value(15000));
        }

        @Test
        @DisplayName("returns empty array when no data")
        void returnsEmptyArray() throws Exception {
            when(ohlcService.getCandles(anyLong(), any(), anyLong(), anyLong())).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/market-data/candles")
                            .param("token", "256265")
                            .param("interval", "5m")
                            .param("from", "1700000000000")
                            .param("to", "1700000300000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("returns 400 for missing required parameters")
        void returns400ForMissingParams() throws Exception {
            mockMvc.perform(get("/api/market-data/candles").param("token", "256265"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("supports all interval types")
        void supportsAllIntervals() throws Exception {
            when(ohlcService.getCandles(anyLong(), any(), anyLong(), anyLong())).thenReturn(Collections.emptyList());

            for (String interval : List.of("1m", "5m", "15m", "1h")) {
                mockMvc.perform(get("/api/market-data/candles")
                                .param("token", "256265")
                                .param("interval", interval)
                                .param("from", "1700000000000")
                                .param("to", "1700000300000"))
                        .andExpect(status().isOk());
            }
        }
    }
}
