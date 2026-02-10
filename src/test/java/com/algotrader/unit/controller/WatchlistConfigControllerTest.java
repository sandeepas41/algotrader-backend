package com.algotrader.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.algotrader.api.controller.WatchlistConfigController;
import com.algotrader.api.dto.response.WatchlistConfigResponse;
import com.algotrader.config.ApiResponseAdvice;
import com.algotrader.domain.enums.ExpiryType;
import com.algotrader.domain.model.WatchlistConfig;
import com.algotrader.mapper.WatchlistConfigMapper;
import com.algotrader.service.WatchlistConfigService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc tests for the WatchlistConfigController.
 * Tests CRUD endpoints for watchlist subscription configs.
 */
@ExtendWith(MockitoExtension.class)
class WatchlistConfigControllerTest {

    private MockMvc mockMvc;

    @Mock
    private WatchlistConfigService watchlistConfigService;

    @Mock
    private WatchlistConfigMapper watchlistConfigMapper;

    @BeforeEach
    void setUp() {
        WatchlistConfigController controller =
                new WatchlistConfigController(watchlistConfigService, watchlistConfigMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiResponseAdvice())
                .build();
    }

    @Test
    @DisplayName("GET /api/watchlist-configs returns all configs")
    void getAllReturnsConfigs() throws Exception {
        WatchlistConfig domain = buildDomain(1L, "NIFTY", 10, true);
        WatchlistConfigResponse response = buildResponse(1L, "NIFTY", 10, true);

        when(watchlistConfigService.getAll()).thenReturn(List.of(domain));
        when(watchlistConfigMapper.toResponseList(List.of(domain))).thenReturn(List.of(response));

        mockMvc.perform(get("/api/watchlist-configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].underlying").value("NIFTY"))
                .andExpect(jsonPath("$.data[0].strikesFromAtm").value(10))
                .andExpect(jsonPath("$.data[0].enabled").value(true));
    }

    @Test
    @DisplayName("POST /api/watchlist-configs creates a new config")
    void createReturnsCreated() throws Exception {
        WatchlistConfig created = buildDomain(1L, "BANKNIFTY", 15, true);
        WatchlistConfigResponse response = buildResponse(1L, "BANKNIFTY", 15, true);

        when(watchlistConfigMapper.toDomain(any(com.algotrader.api.dto.request.WatchlistConfigRequest.class)))
                .thenReturn(WatchlistConfig.builder()
                        .underlying("BANKNIFTY")
                        .strikesFromAtm(15)
                        .expiryType(ExpiryType.NEAREST_WEEKLY)
                        .enabled(true)
                        .build());
        when(watchlistConfigService.create(any())).thenReturn(created);
        when(watchlistConfigMapper.toResponse(created)).thenReturn(response);

        mockMvc.perform(post("/api/watchlist-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"underlying":"BANKNIFTY","strikesFromAtm":15,"expiryType":"NEAREST_WEEKLY"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.underlying").value("BANKNIFTY"))
                .andExpect(jsonPath("$.data.strikesFromAtm").value(15));
    }

    @Test
    @DisplayName("DELETE /api/watchlist-configs/{id} deletes config")
    void deleteReturnsOk() throws Exception {
        mockMvc.perform(delete("/api/watchlist-configs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Watchlist config deleted"));
    }

    @Test
    @DisplayName("PATCH /api/watchlist-configs/{id}/toggle toggles enabled state")
    void toggleReturnsToggled() throws Exception {
        WatchlistConfig toggled = buildDomain(1L, "NIFTY", 10, false);
        WatchlistConfigResponse response = buildResponse(1L, "NIFTY", 10, false);

        when(watchlistConfigService.toggle(1L)).thenReturn(toggled);
        when(watchlistConfigMapper.toResponse(toggled)).thenReturn(response);

        mockMvc.perform(patch("/api/watchlist-configs/1/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    // ---- Helpers ----

    private WatchlistConfig buildDomain(Long id, String underlying, int strikesFromAtm, boolean enabled) {
        return WatchlistConfig.builder()
                .id(id)
                .underlying(underlying)
                .strikesFromAtm(strikesFromAtm)
                .expiryType(ExpiryType.NEAREST_WEEKLY)
                .enabled(enabled)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private WatchlistConfigResponse buildResponse(Long id, String underlying, int strikesFromAtm, boolean enabled) {
        return WatchlistConfigResponse.builder()
                .id(id)
                .underlying(underlying)
                .strikesFromAtm(strikesFromAtm)
                .expiryType("NEAREST_WEEKLY")
                .enabled(enabled)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
